package com.audora.lotting_be.service;

import com.audora.lotting_be.model.customer.Customer;
import com.audora.lotting_be.model.customer.DepositHistory;
import com.audora.lotting_be.repository.CustomerRepository;
import com.audora.lotting_be.repository.DepositHistoryRepository;
import jakarta.transaction.Transactional;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class DepositExcelService {

    private static final Logger logger = LoggerFactory.getLogger(DepositExcelService.class);

    private final DepositHistoryRepository depositHistoryRepository;
    private final CustomerRepository customerRepository;
    private final DepositHistoryService depositHistoryService;

    public DepositExcelService(DepositHistoryRepository depositHistoryRepository,
                               CustomerRepository customerRepository,
                               DepositHistoryService depositHistoryService) {
        this.depositHistoryRepository = depositHistoryRepository;
        this.customerRepository = customerRepository;
        this.depositHistoryService = depositHistoryService;
    }

    public void processDepositExcelFileWithProgress(MultipartFile file, SseEmitter emitter) throws IOException {
        DataFormatter formatter = new DataFormatter(Locale.getDefault());
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        try (InputStream is = file.getInputStream();
             XSSFWorkbook workbook = new XSSFWorkbook(is)) {

            XSSFSheet sheet = workbook.getSheetAt(0);
            int startRow = 1; // 첫 행은 헤더
            int lastRow = sheet.getLastRowNum();
            int totalRows = lastRow - startRow + 1;
            logger.info("총 {}건의 행을 처리합니다.", totalRows);

            for (int i = startRow; i <= lastRow; i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    logger.warn("행 {}가 null입니다. 건너뜁니다.", i);
                    continue;
                }

                try {
                    DepositHistory dh = new DepositHistory();

                    // A: 거래 id (인덱스 0)
                    String idStr = formatter.formatCellValue(row.getCell(0));
                    if (!idStr.isEmpty()) {
                        try {
                            dh.setId(Long.parseLong(idStr.replaceAll("[^0-9]", "")));
                        } catch (NumberFormatException e) {
                            logger.warn("행 {}: 거래 id 파싱 실패 - {}", i, e.getMessage());
                        }
                    }

                    Cell cellB = row.getCell(1);
                    LocalDateTime transactionDateTime = null;
                    if (cellB != null) {
                        // 우선 DataFormatter로 문자열 추출
                        String dateStr = formatter.formatCellValue(cellB);
                        if (!dateStr.isEmpty()) {
                            // 1) 개행(\n, \r\n) 제거(공백 치환)
                            String cleaned = dateStr.replaceAll("[\r\n]+", " ").trim();
                            // 예: "2022.03.18\n15:17:42" → "2022.03.18 15:17:42"

                            // 2) 복수 패턴 시도
                            String[] patterns = {
                                    "yyyy.MM.dd HH:mm:ss",
                                    "yyyy-MM-dd HH:mm:ss",
                                    // 필요하다면 더 추가 가능
                            };

                            for (String pattern : patterns) {
                                try {
                                    DateTimeFormatter customDtf = DateTimeFormatter.ofPattern(pattern);
                                    transactionDateTime = LocalDateTime.parse(cleaned, customDtf);
                                    // 성공하면 루프 탈출
                                    break;
                                } catch (Exception ex) {
                                    // 실패하면 다음 패턴으로 넘어감
                                }
                            }

                            if (transactionDateTime == null) {
                                logger.warn("행 {}: 거래일시 '{}' 파싱 실패 (시도한 패턴: {})", i, cleaned, String.join(", ", patterns));
                            }
                        }
                    }

                    dh.setTransactionDateTime(transactionDateTime);

                    // C: 적요 (인덱스 2)
                    dh.setDescription(formatter.formatCellValue(row.getCell(2)));

                    // D: 기재내용 (인덱스 3)
                    dh.setDetails(formatter.formatCellValue(row.getCell(3)));

                    // E: 계약자 (인덱스 4) → 고객 식별자로 활용
                    String contractor = formatter.formatCellValue(row.getCell(4)).trim();
                    dh.setContractor(contractor);
                    if (!contractor.isEmpty()) {
                        Optional<Customer> customerOpt = customerRepository.findByCustomerDataName(contractor);
                        if (customerOpt.isPresent()) {
                            dh.setCustomer(customerOpt.get());
                        } else {
                            // 고객 이름과 일치하는 결과가 없으면 기본 고객(id:1)에 할당
                            Optional<Customer> defaultCustomerOpt = customerRepository.findById(1);
                            if (defaultCustomerOpt.isPresent()) {
                                dh.setCustomer(defaultCustomerOpt.get());
                            } else {
                                logger.warn("행 {}: 기본 고객(id:1)을 찾을 수 없습니다.", i);
                            }
                        }
                    }

                    // F: 찾으신금액 (인덱스 5)
                    String withdrawnStr = formatter.formatCellValue(row.getCell(5));
                    if (!withdrawnStr.isEmpty()) {
                        try {
                            dh.setWithdrawnAmount(Long.parseLong(withdrawnStr.replaceAll("[^0-9]", "")));
                        } catch (Exception ex) {
                            logger.warn("행 {}: 찾으신금액 파싱 실패 - {}", i, ex.getMessage());
                        }
                    }

                    // G: 맡기신금액 (인덱스 6)
                    String depositAmtStr = formatter.formatCellValue(row.getCell(6));
                    if (!depositAmtStr.isEmpty()) {
                        try {
                            dh.setDepositAmount(Long.parseLong(depositAmtStr.replaceAll("[^0-9]", "")));
                        } catch (Exception ex) {
                            logger.warn("행 {}: 맡기신금액 파싱 실패 - {}", i, ex.getMessage());
                        }
                    }

                    // H: 거래후잔액 (인덱스 7)
                    String balanceStr = formatter.formatCellValue(row.getCell(7));
                    if (!balanceStr.isEmpty()) {
                        try {
                            dh.setBalanceAfter(Long.parseLong(balanceStr.replaceAll("[^0-9]", "")));
                        } catch (Exception ex) {
                            logger.warn("행 {}: 거래후잔액 파싱 실패 - {}", i, ex.getMessage());
                        }
                    }

                    // I: 취급점 (인덱스 8)
                    dh.setBranch(formatter.formatCellValue(row.getCell(8)));

                    // J: 계좌 (인덱스 9)
                    dh.setAccount(formatter.formatCellValue(row.getCell(9)));


                    // V: selfRecord (인덱스 21)
                    String selfRecord = formatter.formatCellValue(row.getCell(21)).trim();
                    dh.setSelfRecord(selfRecord);

                    // W: loanRecord (인덱스 22)
                    String loanRecord = formatter.formatCellValue(row.getCell(22)).trim();
                    dh.setLoanRecord(loanRecord);

                    // selfRecord와 loanRecord 중 하나라도 값이 있으면 loanStatus를 "o"로 설정
                    if (!selfRecord.isEmpty() || !loanRecord.isEmpty()) {
                        dh.setLoanStatus("o");
                    } else {
                        dh.setLoanStatus("");
                    }

                    // loanStatus가 "o"라면, depositPhase1~10 중 값이 있는 항목의 Phase 번호를 targetPhases에 추가
                    if ("o".equals(dh.getLoanStatus())) {
                        ArrayList<Integer> targetPhases = new ArrayList<>();
                        if (formatter.formatCellValue(row.getCell(11)) != null && !formatter.formatCellValue(row.getCell(11)).trim().isEmpty()) {
                            targetPhases.add(1);
                        }
                        if (formatter.formatCellValue(row.getCell(12)) != null && !formatter.formatCellValue(row.getCell(12)).trim().isEmpty()) {
                            targetPhases.add(2);
                        }
                        if (formatter.formatCellValue(row.getCell(13)) != null && !formatter.formatCellValue(row.getCell(13)).trim().isEmpty()) {
                            targetPhases.add(3);
                        }
                        if (formatter.formatCellValue(row.getCell(14)) != null && !formatter.formatCellValue(row.getCell(14)).trim().isEmpty()) {
                            targetPhases.add(4);
                        }
                        if (formatter.formatCellValue(row.getCell(15)) != null && !formatter.formatCellValue(row.getCell(15)).trim().isEmpty()) {
                            targetPhases.add(5);
                        }
                        if (formatter.formatCellValue(row.getCell(16)) != null && !formatter.formatCellValue(row.getCell(16)).trim().isEmpty()) {
                            targetPhases.add(6);
                        }
                        if (formatter.formatCellValue(row.getCell(17)) != null && !formatter.formatCellValue(row.getCell(17)).trim().isEmpty()) {
                            targetPhases.add(7);
                        }
                        if (formatter.formatCellValue(row.getCell(18)) != null && !formatter.formatCellValue(row.getCell(18)).trim().isEmpty()) {
                            targetPhases.add(8);
                        }
                        if (formatter.formatCellValue(row.getCell(19)) != null && !formatter.formatCellValue(row.getCell(19)).trim().isEmpty()) {
                            targetPhases.add(9);
                        }
                        if (formatter.formatCellValue(row.getCell(20)) != null && !formatter.formatCellValue(row.getCell(20)).trim().isEmpty()) {
                            targetPhases.add(10);
                        }
                        dh.setTargetPhases(targetPhases);
                    }

                    // 저장 및 재계산 호출
                    depositHistoryService.createDepositHistory(dh);
                    logger.info("행 {} 처리 완료.", i);

                } catch (Exception e) {
                    logger.error("행 {} 처리 중 예외 발생: {}", i, e.getMessage());
                    // 문제 발생한 행은 건너뛰고 계속 진행
                }

                // 10건마다 또는 마지막 행에서 진행률 전송
                if ((i - startRow + 1) % 10 == 0 || i == lastRow) {
                    try {
                        String progressMsg = (i - startRow + 1) + "/" + totalRows;
                        emitter.send(SseEmitter.event().name("progress").data(progressMsg));
                        logger.info("진행 상황: {}", progressMsg);
                    } catch (Exception ex) {
                        logger.warn("행 {}에서 진행 상황 전송 중 오류: {}", i, ex.getMessage());
                    }
                }
            }

            emitter.send(SseEmitter.event().name("complete").data("Deposit excel processing complete."));
            emitter.complete();
        } catch (IOException e) {
            logger.error("엑셀 파일 처리 중 IOException 발생: {}", e.getMessage());
            try {
                emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
            } catch (Exception ex) {
                logger.error("에러 이벤트 전송 중 예외 발생: {}", ex.getMessage());
            }
            emitter.completeWithError(e);
        }
    }

    @Transactional
    public void fillDepFormat(File tempFile, List<DepositHistory> depositHistories) throws IOException {
        // 데이터 포매터 및 날짜 포맷터 준비
        DataFormatter formatter = new DataFormatter(Locale.getDefault());
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

        try (FileInputStream fis = new FileInputStream(tempFile);
             XSSFWorkbook workbook = new XSSFWorkbook(fis)) {

            XSSFSheet sheet = workbook.getSheetAt(0);
            int startRow = 1; // 0번 행은 헤더

            for (int i = 0; i < depositHistories.size(); i++) {
                DepositHistory dh = depositHistories.get(i);
                Row row = sheet.getRow(startRow + i);
                if (row == null) {
                    row = sheet.createRow(startRow + i);
                }
                int col = 0;
                Cell cell;

                // Column 0: DepositHistory ID
                cell = row.getCell(col);
                if (cell == null) { cell = row.createCell(col); }
                cell.setCellValue(dh.getId() != null ? dh.getId() : 0);
                col++;

                // Column 1: 거래일시
                cell = row.getCell(col);
                if (cell == null) { cell = row.createCell(col); }
                cell.setCellValue(dh.getTransactionDateTime() != null ? dh.getTransactionDateTime().format(dtf) : "");
                col++;

                // Column 2: 적요
                cell = row.getCell(col);
                if (cell == null) { cell = row.createCell(col); }
                cell.setCellValue(dh.getDescription() != null ? dh.getDescription() : "");
                col++;

                // Column 3: 기재내용
                cell = row.getCell(col);
                if (cell == null) { cell = row.createCell(col); }
                cell.setCellValue(dh.getDetails() != null ? dh.getDetails() : "");
                col++;

                // Column 4: 계약자
                cell = row.getCell(col);
                if (cell == null) { cell = row.createCell(col); }
                cell.setCellValue(dh.getContractor() != null ? dh.getContractor() : "");
                col++;

                // Column 5: 찾으신금액
                cell = row.getCell(col);
                if (cell == null) { cell = row.createCell(col); }
                cell.setCellValue(dh.getWithdrawnAmount() != null ? dh.getWithdrawnAmount() : 0);
                col++;

                // Column 6: 맡기신금액
                cell = row.getCell(col);
                if (cell == null) { cell = row.createCell(col); }
                cell.setCellValue(dh.getDepositAmount() != null ? dh.getDepositAmount() : 0);
                col++;

                // Column 7: 거래후 잔액
                cell = row.getCell(col);
                if (cell == null) { cell = row.createCell(col); }
                cell.setCellValue(dh.getBalanceAfter() != null ? dh.getBalanceAfter() : 0);
                col++;

                // Column 8: 취급점
                cell = row.getCell(col);
                if (cell == null) { cell = row.createCell(col); }
                cell.setCellValue(dh.getBranch() != null ? dh.getBranch() : "");
                col++;

                // Column 9: 계좌
                cell = row.getCell(col);
                if (cell == null) { cell = row.createCell(col); }
                cell.setCellValue(dh.getAccount() != null ? dh.getAccount() : "");
                col++;

                // Column 10: depositPhase1
                cell = row.getCell(col);
                if (cell == null) { cell = row.createCell(col); }
                cell.setCellValue(dh.getDepositPhase1() != null ? dh.getDepositPhase1() : "");
                col++;

                // Column 11: depositPhase2
                cell = row.getCell(col);
                if (cell == null) { cell = row.createCell(col); }
                cell.setCellValue(dh.getDepositPhase2() != null ? dh.getDepositPhase2() : "");
                col++;

                // Column 12: depositPhase3
                cell = row.getCell(col);
                if (cell == null) { cell = row.createCell(col); }
                cell.setCellValue(dh.getDepositPhase3() != null ? dh.getDepositPhase3() : "");
                col++;

                // Column 13: depositPhase4
                cell = row.getCell(col);
                if (cell == null) { cell = row.createCell(col); }
                cell.setCellValue(dh.getDepositPhase4() != null ? dh.getDepositPhase4() : "");
                col++;

                // Column 14: depositPhase5
                cell = row.getCell(col);
                if (cell == null) { cell = row.createCell(col); }
                cell.setCellValue(dh.getDepositPhase5() != null ? dh.getDepositPhase5() : "");
                col++;

                // Column 15: depositPhase6
                cell = row.getCell(col);
                if (cell == null) { cell = row.createCell(col); }
                cell.setCellValue(dh.getDepositPhase6() != null ? dh.getDepositPhase6() : "");
                col++;

                // Column 16: depositPhase7
                cell = row.getCell(col);
                if (cell == null) { cell = row.createCell(col); }
                cell.setCellValue(dh.getDepositPhase7() != null ? dh.getDepositPhase7() : "");
                col++;

                // Column 17: depositPhase8
                cell = row.getCell(col);
                if (cell == null) { cell = row.createCell(col); }
                cell.setCellValue(dh.getDepositPhase8() != null ? dh.getDepositPhase8() : "");
                col++;

                // Column 18: depositPhase9
                cell = row.getCell(col);
                if (cell == null) { cell = row.createCell(col); }
                cell.setCellValue(dh.getDepositPhase9() != null ? dh.getDepositPhase9() : "");
                col++;

                // Column 19: depositPhase10
                cell = row.getCell(col);
                if (cell == null) { cell = row.createCell(col); }
                cell.setCellValue(dh.getDepositPhase10() != null ? dh.getDepositPhase10() : "");
                col++;

                // Column 20: selfRec
                cell = row.getCell(col);
                if (cell == null) { cell = row.createCell(col); }
                cell.setCellValue(dh.getSelfRecord() != null ? dh.getSelfRecord() : "");
                col++;

                // Column 21: loanRecord
                cell = row.getCell(col);
                if (cell == null) { cell = row.createCell(col); }
                cell.setCellValue(dh.getLoanRecord() != null ? dh.getLoanRecord() : "");
                col++;

            } // for end

            workbook.setForceFormulaRecalculation(true);
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                workbook.write(fos);
            }
        }
    }



}
