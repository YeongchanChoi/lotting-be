package com.audora.lotting_be.service;

import com.audora.lotting_be.model.Fee.Fee;
import com.audora.lotting_be.model.Fee.FeePerPhase;
import com.audora.lotting_be.model.customer.Customer;
import com.audora.lotting_be.model.customer.Phase;
import com.audora.lotting_be.model.customer.Status;
import com.audora.lotting_be.payload.response.CustomerDepositDTO;
import com.audora.lotting_be.payload.response.LateFeeInfo;
import com.audora.lotting_be.repository.CustomerRepository;
import com.audora.lotting_be.repository.FeeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CustomerService {

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private FeeRepository feeRepository;

    /**
     * 다음 고객 ID를 반환합니다.
     * @return 다음 고객 ID
     */
    public Integer getNextCustomerId() {
        return customerRepository.getNextId();
    }

    /**
     * 주어진 ID로 고객을 조회합니다.
     * @param id 고객 ID
     * @return 해당 고객 또는 null
     */
    public Customer getCustomerById(Integer id) {
        Optional<Customer> optionalCustomer = customerRepository.findById(id);
        return optionalCustomer.orElse(null);
    }

    /**
     * 새로운 고객을 생성합니다.
     * @param customer 생성할 고객 정보
     * @return 생성된 고객
     */
    public Customer createCustomer(Customer customer) {
        // ID 중복 확인
        if (customerRepository.existsById(customer.getId())) {
            throw new IllegalArgumentException("이미 존재하는 관리번호입니다.");
        }

        // type + groupname, batch로 Fee 조회
        Fee fee = feeRepository.findByGroupnameAndBatch(
                customer.getType() + customer.getGroupname(),
                customer.getBatch()
        );

        // Fee 정보가 있다면 해당 정보를 기반으로 Phase 초기화
        if (fee != null) {
            List<FeePerPhase> feePerPhases = fee.getFeePerPhases();
            List<Phase> phases = new ArrayList<>();

            for (FeePerPhase feePerPhase : feePerPhases) {
                Phase phase = new Phase();
                phase.setPhaseNumber(feePerPhase.getPhaseNumber());
                phase.setCharge(feePerPhase.getPhasefee());

                // --- 추가한 로직: phasedateString 세팅 ---
                phase.setPlanneddateString(feePerPhase.getPhasedate());

                // discount, exemption, service는 0으로 가정
                Long discount = 0L;
                Long exemption = 0L;
                Long service = 0L;

                // feesum = charge - discount - exemption + service
                Long feesum = feePerPhase.getPhasefee() - discount - exemption + service;
                phase.setFeesum(feesum);

                // sum을 초기에는 총액(phasefee) 그대로
                phase.setSum(feePerPhase.getPhasefee());

                // 기존 예정일자 계산 로직
                LocalDate plannedDate = calculatePlannedDate(
                        customer.getRegisterdate(),
                        feePerPhase.getPhasedate()
                );
                phase.setPlanneddate(plannedDate);

                // 양방향 설정
                phase.setCustomer(customer);
                phases.add(phase);
            }
            customer.setPhases(phases);
        }

        // Status 객체가 null이면 새로 생성
        if (customer.getStatus() == null) {
            Status status = new Status();
            status.setCustomer(customer);
            customer.setStatus(status);
        }

        // Phase 생성 후 Status 필드 업데이트
        updateStatusFields(customer);

        return customerRepository.save(customer);
    }

    /**
     * ~달 / ~개월 / ~년 형태의 문자열을 해석하여
     * registerDate에 연, 달을 더한 날짜를 반환
     */
    private LocalDate calculatePlannedDate(LocalDate registerDate, String phasedate) {
        if (phasedate == null || phasedate.isEmpty()) {
            // null 또는 빈 문자열이면 등록일 그대로 반환
            return registerDate;
        }

        // '달' 또는 '개월'로 끝나는 경우
        if (phasedate.endsWith("달") || phasedate.endsWith("개월")) {
            int months = Integer.parseInt(phasedate.replaceAll("[^0-9]", ""));
            return registerDate.plusMonths(months);
        }
        // '년'으로 끝나는 경우
        else if (phasedate.endsWith("년")) {
            int years = Integer.parseInt(phasedate.replaceAll("[^0-9]", ""));
            return registerDate.plusYears(years);
        }
        // 위 두 가지 경우가 아니면 registerDate 그대로
        else {
            return registerDate.plusYears(100);
        }
    }

    /**
     * 고객의 Status 필드를 업데이트 합니다.
     * exemptionsum, unpaidammout, unpaidphase, ammountsum 등을 갱신.
     */
    public void updateStatusFields(Customer customer) {
        Status status = customer.getStatus();

        Long exemptionsum = customer.getPhases().stream()
                .mapToLong(phase -> phase.getExemption() != null ? phase.getExemption() : 0L)
                .sum();
        status.setExemptionsum(exemptionsum);

        Long unpaidammout = customer.getPhases().stream()
                .mapToLong(phase -> phase.getSum() != null ? phase.getSum() : 0L)
                .sum();
        status.setUnpaidammout(unpaidammout);

        List<Integer> unpaidPhaseNumbers = customer.getPhases().stream()
                .filter(phase -> phase.getPlanneddate() != null
                        && phase.getPlanneddate().isBefore(LocalDate.now())
                        && phase.getFullpaiddate() == null)
                .map(Phase::getPhaseNumber)
                .sorted()
                .collect(Collectors.toList());

        String unpaidPhaseStr = unpaidPhaseNumbers.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        status.setUnpaidphase(unpaidPhaseStr);

        Long ammountsum = customer.getPhases().stream()
                .mapToLong(phase -> phase.getFeesum() != null ? phase.getFeesum() : 0L)
                .sum();
        status.setAmmountsum(ammountsum);

        customer.setStatus(status);
    }

    /**
     * 이름과 번호로 고객을 검색합니다.
     * 번호로 검색할 때도 부분 일치를 지원합니다.
     *
     * @param name   검색할 이름 (선택 사항)
     * @param number 검색할 번호 (선택 사항, 부분 일치 가능)
     * @return 검색된 고객 리스트
     */
    public List<Customer> searchCustomers(String name, String number) {
        if (name != null && number != null) {
            if (number.matches("\\d+")) {
                return customerRepository.findByNameContainingAndIdContaining(name, number);
            } else {
                // 번호가 숫자가 아닐 경우 이름으로만 검색
                return customerRepository.findByCustomerDataNameContaining(name);
            }
        } else if (name != null) {
            return customerRepository.findByCustomerDataNameContaining(name);
        } else if (number != null) {
            if (number.matches("\\d+")) {
                return customerRepository.findByIdContaining(number);
            } else {
                return Collections.emptyList();
            }
        } else {
            return customerRepository.findAll();
        }
    }

    /**
     * 고객을 삭제합니다.
     */
    public void deleteCustomer(Integer id) {
        customerRepository.deleteById(id);
    }

    /**
     * 납부전(Pending) 차수를 가져옵니다.
     */
    public List<Phase> getPendingPhases(Integer customerId) {
        Optional<Customer> customerOptional = customerRepository.findById(customerId);
        if (customerOptional.isPresent()) {
            Customer customer = customerOptional.get();
            List<Phase> phases = customer.getPhases();
            return phases.stream()
                    .filter(phase -> phase.getSum() != null && phase.getSum() > 0)
                    .collect(Collectors.toList());
        } else {
            return null;
        }
    }

    /**
     * 납부 완료된(Completed) 차수를 가져옵니다.
     */
    public List<Phase> getCompletedPhases(Integer customerId) {
        Optional<Customer> customerOptional = customerRepository.findById(customerId);
        if (customerOptional.isPresent()) {
            Customer customer = customerOptional.get();
            List<Phase> phases = customer.getPhases();
            return phases.stream()
                    .filter(phase -> phase.getSum() == null || phase.getSum() == 0)
                    .collect(Collectors.toList());
        } else {
            return null;
        }
    }

    /**
     * 고객 정보를 저장합니다.
     */
    public Customer saveCustomer(Customer customer) {
        return customerRepository.save(customer);
    }

    /**
     * 고객을 해지처리(customertype='c')로 변경합니다.
     */
    public boolean cancelCustomer(Integer id) {
        Optional<Customer> optionalCustomer = customerRepository.findById(id);
        if (optionalCustomer.isPresent()) {
            Customer customer = optionalCustomer.get();
            customer.setCustomertype("c");
            customerRepository.save(customer);
            return true;
        } else {
            return false;
        }
    }

    /**
     * 연체료 정보를 가져오는 메서드입니다.
     * name과 number로 필터링하며, 해당 고객들의 연체 정보를 계산합니다.
     */
    public List<LateFeeInfo> getLateFeeInfos(String name, String number) {
        List<Customer> customers;

        // name, number 파라미터로 고객 필터링
        if (name != null && !name.isEmpty() && number != null && !number.isEmpty()) {
            try {
                Integer id = Integer.parseInt(number);
                customers = customerRepository.findByCustomerDataNameAndId(name, id);
            } catch (NumberFormatException e) {
                customers = Collections.emptyList();
            }
        } else if (name != null && !name.isEmpty()) {
            customers = customerRepository.findByCustomerDataNameContaining(name);
        } else if (number != null && !number.isEmpty()) {
            try {
                Integer id = Integer.parseInt(number);
                customers = customerRepository.findById(id)
                        .map(Collections::singletonList)
                        .orElse(Collections.emptyList());
            } catch (NumberFormatException e) {
                customers = Collections.emptyList();
            }
        } else {
            customers = customerRepository.findAll();
        }

        List<LateFeeInfo> lateFeeInfos = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (Customer customer : customers) {
            List<Phase> phases = customer.getPhases();
            if (phases == null || phases.isEmpty()) {
                // 차수가 없으면 스킵
                continue;
            }

            // 미납된 차수: planneddate가 오늘 이전이고 fullpaiddate가 null인 경우
            List<Phase> unpaidPhases = phases.stream()
                    .filter(phase -> phase.getPlanneddate() != null
                            && phase.getPlanneddate().isBefore(today)
                            && phase.getFullpaiddate() == null)
                    .collect(Collectors.toList());

            LateFeeInfo info = new LateFeeInfo();
            info.setId(customer.getId());
            info.setCustomertype(
                    customer.getCustomertype() != null ? customer.getCustomertype() : "N/A"
            );
            info.setName(
                    customer.getCustomerData() != null && customer.getCustomerData().getName() != null
                            ? customer.getCustomerData().getName()
                            : "N/A"
            );
            info.setRegisterdate(
                    customer.getRegisterdate() != null ? customer.getRegisterdate() : null
            );

            if (unpaidPhases.isEmpty()) {
                // 미납 없는 회원
                info.setLastUnpaidPhaseNumber(null);
                info.setLateBaseDate(null);
                info.setRecentPaymentDate(null);
                info.setDaysOverdue(0L);
                info.setLateRate(0.0);
                info.setOverdueAmount(0L);
                Long paidAmount = phases.stream()
                        .mapToLong(p -> p.getCharged() != null ? p.getCharged() : 0L)
                        .sum();
                info.setPaidAmount(paidAmount);
                info.setLateFee(0.0);
                info.setTotalOwed(0L);
                lateFeeInfos.add(info);
            } else {
                // 미납 회원 로직
                // 1. 마지막 미납 차수 번호
                int lastUnpaidPhaseNumber = unpaidPhases.stream()
                        .mapToInt(Phase::getPhaseNumber)
                        .max()
                        .orElse(0);
                info.setLastUnpaidPhaseNumber(lastUnpaidPhaseNumber);

                // 2. 연체기준일 (미납차수 중 가장 이른 planneddate)
                LocalDate lateBaseDate = unpaidPhases.stream()
                        .map(Phase::getPlanneddate)
                        .min(LocalDate::compareTo)
                        .orElse(null);
                info.setLateBaseDate(lateBaseDate);

                // 3. 최근납부일 (납부 완료된 phase 중 가장 최근 fullpaiddate)
                List<Phase> paidPhases = phases.stream()
                        .filter(p -> p.getFullpaiddate() != null)
                        .collect(Collectors.toList());
                LocalDate recentPaymentDate = paidPhases.stream()
                        .map(Phase::getFullpaiddate)
                        .max(LocalDate::compareTo)
                        .orElse(null);
                info.setRecentPaymentDate(recentPaymentDate);

                // 4. 연체일수(daysOverdue)
                long daysOverdue = lateBaseDate != null
                        ? ChronoUnit.DAYS.between(lateBaseDate, today)
                        : 0;
                if (daysOverdue < 0) daysOverdue = 0; // 연체기준일이 오늘 이후라면 연체일수는 0
                info.setDaysOverdue(daysOverdue);

                // 5. 연체율(lateRate) 가정: 일 0.05% (0.0005)
                double lateRate = 0.0005;
                info.setLateRate(lateRate);

                // 6. 연체금액(overdueAmount): 미납 phase들의 feesum 합
                long overdueAmount = unpaidPhases.stream()
                        .mapToLong(p -> p.getFeesum() != null ? p.getFeesum() : 0L)
                        .sum();
                info.setOverdueAmount(overdueAmount);

                // 7. 납입금액(paidAmount): 전체 phase 중 charged 합
                long paidAmount = phases.stream()
                        .mapToLong(p -> p.getCharged() != null ? p.getCharged() : 0L)
                        .sum();
                info.setPaidAmount(paidAmount);

                // 8. 연체료(lateFee) 계산 예시: overdueAmount * lateRate * daysOverdue
                double lateFee = overdueAmount * lateRate * daysOverdue;
                info.setLateFee(lateFee);

                // 9. 내야할 돈 합계(totalOwed): 미납액 + 연체료 (연체료는 반올림 가능)
                long totalOwed = overdueAmount + Math.round(lateFee);
                info.setTotalOwed(totalOwed);

                lateFeeInfos.add(info);
            }
        }

        return lateFeeInfos;
    }

    /**
     * 정계약한 사람들(customertype = 'c')의 숫자 반환
     */
    public long countContractedCustomers() {
        return customerRepository.countByCustomertype("c");
    }

    /**
     * 미납이 아닌(모든 예정금액 납부완료 또는 예정일이 아직 지나지 않은) 회원 수 반환
     */
    public long countFullyPaidOrNotOverdueCustomers() {
        List<Customer> allCustomers = customerRepository.findAll();
        LocalDate today = LocalDate.now();

        return allCustomers.stream()
                .filter(customer -> {
                    List<Phase> phases = customer.getPhases();
                    if (phases == null || phases.isEmpty()) {
                        // 차수 정보가 없으면 미납은 아니라고 가정
                        return true;
                    }

                    // 미납 기준: planneddate < today && fullpaiddate == null
                    // 미납 phase가 하나라도 있으면 false
                    boolean hasOverdue = phases.stream()
                            .anyMatch(phase -> phase.getPlanneddate() != null
                                    && phase.getPlanneddate().isBefore(today)
                                    && phase.getFullpaiddate() == null);

                    return !hasOverdue; // 미납이 아닌 경우 true
                })
                .count();
    }

    /**
     * 모든 회원의 입금 기록 DTO 리스트 반환
     * (수정: 요구사항에 맞춰 DTO 구성 보강)
     */
    public List<CustomerDepositDTO> getAllCustomerDepositDTOs() {
        // 모든 고객 조회
        List<Customer> allCustomers = customerRepository.findAll();

        // Customer -> CustomerDepositDTO 변환
        return allCustomers.stream()
                .map(this::mapToCustomerDepositDTO)
                .collect(Collectors.toList());
    }

    /**
     * Customer -> CustomerDepositDTO 로 매핑하는 전용 메서드
     */
    private CustomerDepositDTO mapToCustomerDepositDTO(Customer customer) {
        CustomerDepositDTO dto = new CustomerDepositDTO();

        // 1) memberNumber: 고객의 id
        dto.setMemberNumber(customer.getId());

        // 2) lastTransactionDateTime: phase 중 가장 최근 fullpaiddate를 찾아 LocalDateTime으로
        LocalDate lastPaidDate = customer.getPhases().stream()
                .map(Phase::getFullpaiddate)
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);

        if (lastPaidDate != null) {
            dto.setLastTransactionDateTime(lastPaidDate.atStartOfDay());
        } else {
            dto.setLastTransactionDateTime(null);
        }

        // 3) remarks, memo: 임시로 비워둠
        dto.setRemarks("");
        dto.setMemo("");

        // 4) contractor: 가입자명 (customerData.name)
        if (customer.getCustomerData() != null) {
            dto.setContractor(customer.getCustomerData().getName());
        } else {
            dto.setContractor("");
        }

        // 5) withdrawnAmount: 환불금액 (현재 별도 필드가 없으므로 null 처리 or 0L)
        //    실제 환불 로직 있으면 반영
        dto.setWithdrawnAmount(null);

        // 6) depositAmount: 지금까지 입금한 금액 총액
        //    우선 Status.ammountsum이 있다고 가정 -> 없으면 phase.charged 합산
        Long depositAmount;
            depositAmount = customer.getPhases().stream()
                    .mapToLong(p -> p.getCharged() != null ? p.getCharged() : 0L)
                    .sum();

        dto.setDepositAmount(depositAmount);

        // 7) bankBranch: 기존 회원의 은행(financial.bankname 등)
        if (customer.getFinancial() != null && customer.getFinancial().getBankname() != null) {
            dto.setBankBranch(customer.getFinancial().getBankname());
        } else {
            dto.setBankBranch("");
        }

        // 8) account: 임시로 "h"
        dto.setAccount("h");

        // 9) reservation: 임시로 비워두기
        dto.setReservation("");

        // 10) 1~10차 입금 상태
        dto.setDepositPhase1(getPhaseStatus(customer, 1));
        dto.setDepositPhase2(getPhaseStatus(customer, 2));
        dto.setDepositPhase3(getPhaseStatus(customer, 3));
        dto.setDepositPhase4(getPhaseStatus(customer, 4));
        dto.setDepositPhase5(getPhaseStatus(customer, 5));
        dto.setDepositPhase6(getPhaseStatus(customer, 6));
        dto.setDepositPhase7(getPhaseStatus(customer, 7));
        dto.setDepositPhase8(getPhaseStatus(customer, 8));
        dto.setDepositPhase9(getPhaseStatus(customer, 9));
        dto.setDepositPhase10(getPhaseStatus(customer, 10));

        // 11) loanAmount, loanDate
        if (customer.getLoan() != null) {
            dto.setLoanAmount(customer.getLoan().getLoanammount());
            dto.setLoanDate(customer.getLoan().getLoandate());
        } else {
            dto.setLoanAmount(null);
            dto.setLoanDate(null);
        }

        // 12) temporary, note: 임시로 비워두기
        dto.setTemporary("");
        dto.setNote("");

        return dto;
    }

    /**
     * 특정 차수(phaseNumber)에 대한 입금 상태 "o"/"x"를 판단하는 헬퍼 메서드
     */
    private String getPhaseStatus(Customer customer, int phaseNumber) {
        Phase targetPhase = customer.getPhases().stream()
                .filter(p -> p.getPhaseNumber() != null && p.getPhaseNumber() == phaseNumber)
                .findFirst()
                .orElse(null);

        if (targetPhase == null) {
            // 해당 차수가 없으면 "" 반환(혹은 "x")
            return "";
        }

        // charged가 0보다 크면 "o", 아니면 "x"
        Long charged = targetPhase.getCharged();
        return (charged != null && charged > 0) ? "o" : "x";
    }
}
