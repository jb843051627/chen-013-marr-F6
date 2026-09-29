package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fc.v2.mapper.auto.TMarrReportBatchMapper;
import com.fc.v2.mapper.auto.TMarrReportRowHisMapper;
import com.fc.v2.mapper.auto.TMarrReportRowMapper;
import com.fc.v2.model.auto.TMarrReportBatch;
import com.fc.v2.model.auto.TMarrReportRow;
import com.fc.v2.model.auto.TMarrReportRowHis;
import com.fc.v2.model.custom.ReportBatchReceipt;

/**
 * 报送册核收逐行处理规矩的单元测试（内存假库 + Mockito，不启容器、不碰 MySQL）。
 */
class TMarrReportRowServiceImplTest {

    private TMarrReportRowServiceImpl service;

    private final List<TMarrReportRow> rows = Collections.synchronizedList(new ArrayList<>());
    private final List<TMarrReportBatch> heads = Collections.synchronizedList(new ArrayList<>());
    private final List<TMarrReportRowHis> his = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong ids = new AtomicLong(1000L);

    private TMarrReportRow r(String batch, Integer rowNo, String code, String qty) {
        TMarrReportRow row = new TMarrReportRow();
        row.setBatchNo(batch);
        row.setRowNo(rowNo);
        row.setItemCode(code);
        row.setQty(qty == null ? null : new BigDecimal(qty));
        return row;
    }

    /** 走"显式册号+上月底数(空)"的回执重载。 */
    private ReportBatchReceipt submit(String batchNo, List<TMarrReportRow> rows) {
        return service.submitBatch(batchNo, rows, null);
    }

    /** 把 QueryWrapper 里渲染出的 列->值 抽出来（仅本测试用到的 = / <= / IN 三种形状）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, List<Object>> conds(Wrapper<?> w0) {
        com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> w =
                (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) w0;
        String sql = w.getSqlSegment();
        Map<String, Object> params = w.getParamNameValuePairs();
        Map<String, String> tokenToKey = new LinkedHashMap<>();
        String masked = sql;
        for (Map.Entry<String, Object> e : params.entrySet()) {
            String token = "{{" + tokenToKey.size() + "}}";
            tokenToKey.put(token, e.getKey());
            masked = masked.replace("#{ew.paramNameValuePairs." + e.getKey() + "}", token);
        }
        Map<String, List<Object>> out = new LinkedHashMap<>();
        Matcher inM = Pattern.compile("(\\w+)\\s+IN\\s*\\(([^)]*)\\)").matcher(masked);
        while (inM.find()) {
            List<Object> vals = new ArrayList<>();
            for (String t : inM.group(2).split(",")) {
                String key = tokenToKey.get(t.trim());
                vals.add(key == null ? null : params.get(key));
            }
            out.put(inM.group(1), vals);
        }
        Matcher cmpM = Pattern.compile("(\\w+)\\s*(<=|>=|=)\\s*(\\{\\{\\d+}})").matcher(masked);
        while (cmpM.find()) {
            if (out.containsKey(cmpM.group(1))) {
                continue;
            }
            String key = tokenToKey.get(cmpM.group(3));
            out.put(cmpM.group(1), new ArrayList<>(Collections.singletonList(
                    key == null ? null : params.get(key))));
        }
        return out;
    }

    private Object val(Map<String, List<Object>> c, String col) {
        List<Object> v = c.get(col);
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    private boolean matchRow(TMarrReportRow row, Map<String, List<Object>> c) {
        if (c.containsKey("batch_no") && !String.valueOf(c.get("batch_no").get(0)).equals(row.getBatchNo())) {
            return false;
        }
        if (c.containsKey("item_code") && !String.valueOf(c.get("item_code").get(0)).equals(row.getItemCode())) {
            return false;
        }
        if (c.containsKey("del_flag") && !asInt(c.get("del_flag").get(0)).equals(nz(row.getDelFlag()))) {
            return false;
        }
        if (c.containsKey("status")) {
            List<Object> want = c.get("status");
            if (!want.contains(row.getStatus())) {
                return false;
            }
        }
        return true;
    }

    private static Integer asInt(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : Integer.valueOf(String.valueOf(o));
    }

    private static int nz(Integer i) {
        return i == null ? 0 : i;
    }

    private boolean matchHead(TMarrReportBatch h, Map<String, List<Object>> c) {
        if (c.containsKey("batch_no") && !String.valueOf(c.get("batch_no").get(0)).equals(h.getBatchNo())) {
            return false;
        }
        if (c.containsKey("del_flag") && !asInt(c.get("del_flag").get(0)).equals(nz(h.getDelFlag()))) {
            return false;
        }
        if (c.containsKey("result") && !c.get("result").contains(h.getResult())) {
            return false;
        }
        if (c.containsKey("accepted_at")) {
            Date cutoff = (Date) c.get("accepted_at").get(0);
            if (h.getAcceptedAt() == null || h.getAcceptedAt().after(cutoff)) {
                return false;
            }
        }
        return true;
    }

    @BeforeEach
    void setUp() throws Exception {
        service = new TMarrReportRowServiceImpl();

        TMarrReportRowMapper rowMapper = mock(TMarrReportRowMapper.class);
        TMarrReportBatchMapper batchMapper = mock(TMarrReportBatchMapper.class);
        TMarrReportRowHisMapper hisMapper = mock(TMarrReportRowHisMapper.class);
        inject("marrReportRowMapper", rowMapper);
        inject("marrReportBatchMapper", batchMapper);
        inject("marrReportRowHisMapper", hisMapper);

        when(rowMapper.insert(any(TMarrReportRow.class))).thenAnswer(inv -> {
            TMarrReportRow row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(ids.incrementAndGet());
            }
            rows.add(row);
            return 1;
        });
        when(rowMapper.selectList(any())).thenAnswer(inv -> {
            Map<String, List<Object>> c = conds(inv.getArgument(0));
            List<TMarrReportRow> out = new ArrayList<>();
            for (TMarrReportRow row : rows) {
                if (matchRow(row, c)) {
                    out.add(row);
                }
            }
            return out;
        });
        when(rowMapper.selectCount(any())).thenAnswer(inv -> {
            Map<String, List<Object>> c = conds(inv.getArgument(0));
            int n = (int) rows.stream().filter(x -> matchRow(x, c)).count();
            return Integer.valueOf(n);
        });
        when(rowMapper.selectById(any())).thenAnswer(inv -> {
            Long id = Long.valueOf(inv.getArgument(0).toString());
            return rows.stream().filter(x -> id.equals(x.getId())).findFirst().orElse(null);
        });
        doAnswer(inv -> {
            Long id = Long.valueOf(inv.getArgument(0).toString());
            rows.removeIf(x -> id.equals(x.getId()));
            return 1;
        }).when(rowMapper).deleteById(any());
        doAnswer(inv -> 1).when(rowMapper).updateById(any(TMarrReportRow.class));

        when(batchMapper.insert(any(TMarrReportBatch.class))).thenAnswer(inv -> {
            TMarrReportBatch h = inv.getArgument(0);
            boolean dup = heads.stream().anyMatch(x -> x.getBatchNo().equals(h.getBatchNo()) && nz(x.getDelFlag()) == 0);
            if (dup) {
                throw new DuplicateKeyException("uk_report_batch_no");
            }
            if (h.getId() == null) {
                h.setId(ids.incrementAndGet());
            }
            heads.add(h);
            return 1;
        });
        when(batchMapper.selectOne(any())).thenAnswer(inv -> {
            Map<String, List<Object>> c = conds(inv.getArgument(0));
            return heads.stream().filter(x -> matchHead(x, c)).findFirst().orElse(null);
        });
        when(batchMapper.selectList(any())).thenAnswer(inv -> {
            Map<String, List<Object>> c = conds(inv.getArgument(0));
            List<TMarrReportBatch> out = new ArrayList<>();
            for (TMarrReportBatch h : heads) {
                if (matchHead(h, c)) {
                    out.add(h);
                }
            }
            return out;
        });

        when(hisMapper.insert(any(TMarrReportRowHis.class))).thenAnswer(inv -> {
            his.add(inv.getArgument(0));
            return 1;
        });
        when(hisMapper.selectList(any())).thenAnswer(inv -> {
            Map<String, List<Object>> c = conds(inv.getArgument(0));
            List<TMarrReportRowHis> out = new ArrayList<>();
            for (TMarrReportRowHis row : his) {
                if (!c.containsKey("batch_no") || String.valueOf(c.get("batch_no").get(0)).equals(row.getBatchNo())) {
                    if (!c.containsKey("item_code") || String.valueOf(c.get("item_code").get(0)).equals(row.getItemCode())) {
                        out.add(row);
                    }
                }
            }
            return out;
        });
        when(hisMapper.selectCount(any())).thenAnswer(inv -> {
            Map<String, List<Object>> c = conds(inv.getArgument(0));
            int n = (int) his.stream().filter(x ->
                    !c.containsKey("batch_no") || String.valueOf(c.get("batch_no").get(0)).equals(x.getBatchNo()))
                    .count();
            return Integer.valueOf(n);
        });
    }

    private void inject(String name, Object value) throws Exception {
        Field f = TMarrReportRowServiceImpl.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    @Test
    @DisplayName("同册同项两行并一条：对数相加只算一回，行次留先出现的")
    void mergeSameItemSumsAndKeepsFirstRowNo() {
        ReportBatchReceipt rc = submit("B1", Arrays.asList(
                r("B1", 1, "MARRY", "10"),
                r("B1", 2, "MARRY", "5"),
                r("B1", 3, "DIVORCE", "7")));

        assertEquals(0, rc.getResult());
        assertTrue(rc.isSealed());
        assertEquals(2, rc.getRawCount(), "并条后原册记账行数=2");
        assertEquals(2, rc.getAcceptedCount());
        assertEquals(0, rc.getRejectedCount());
        assertEquals(0, new BigDecimal("22").compareTo(rc.getAcceptedQty()));

        List<TMarrReportRow> stored = service.listByBatch("B1");
        assertEquals(2, stored.size());
        TMarrReportRow marry = stored.stream().filter(x -> "MARRY".equals(x.getItemCode())).findFirst().get();
        assertEquals(0, new BigDecimal("15").compareTo(marry.getQty()));
        assertEquals(Integer.valueOf(1), marry.getRowNo(), "行次留先出现的第1行");
    }

    @Test
    @DisplayName("退回只退那一行：负数行挂驳回带具体缘故，其余各行照旧汇纳，不连坐")
    void rejectOnlyTheBadLine() {
        ReportBatchReceipt rc = submit("B2", Arrays.asList(
                r("B2", 1, "MARRY", "12"),
                r("B2", 2, "DIVORCE", "-3"),
                r("B2", 3, "FIXCARD", "40")));

        assertEquals(ReportBatchReceipt.RESULT_PARTIAL, rc.getResult());
        assertFalse(rc.isSealed());
        assertEquals(3, rc.getRawCount());
        assertEquals(2, rc.getAcceptedCount());
        assertEquals(1, rc.getRejectedCount());
        assertTrue(rc.isBalanced(), "挂+汇=原册行数");

        List<TMarrReportRow> bad = rc.getRejectedRows();
        assertEquals(1, bad.size());
        assertEquals(Integer.valueOf(2), bad.get(0).getRowNo(), "屏幕直接点得出是原册第几行");
        assertEquals(Integer.valueOf(2), bad.get(0).getStatus());
        assertNotNull(bad.get(0).getRemark());
        assertTrue(bad.get(0).getRemark().contains("负数"), "缘故一句话讲清是负数这一件事");
        assertFalse(bad.get(0).getRemark().contains("数不对"), "不许写要人猜的字");

        assertEquals(2, service.listByStatus(1).size());
        assertEquals(1, service.listByStatus(2).size());
    }

    @Test
    @DisplayName("空缺对数/空缺代号/空缺行次各有具体缘故，不写数不对")
    void blankFieldsHaveSpecificReasons() {
        ReportBatchReceipt rc = submit("B3", Arrays.asList(
                r("B3", 1, "MARRY", null),
                r("B3", 2, "", "9"),
                r("B3", null, "FIXCARD", "9")));

        assertEquals(0, rc.getAcceptedCount());
        assertEquals(3, rc.getRejectedCount());
        for (TMarrReportRow row : rc.getRejectedRows()) {
            assertNotNull(row.getRemark());
            assertFalse(row.getRemark().trim().isEmpty());
            assertFalse("数不对".equals(row.getRemark()));
        }
        assertTrue(rc.getRejectedRows().stream().anyMatch(x -> x.getRemark().contains("对数空缺")));
        assertTrue(rc.getRejectedRows().stream().anyMatch(x -> x.getRemark().contains("代号空缺")));
        assertTrue(rc.getRejectedRows().stream().anyMatch(x -> x.getRemark().contains("行次空缺")));
    }

    @Test
    @DisplayName("较上月翻出30倍及以上整行退回（边界：30倍退、29倍收）")
    void surgeOverPriorMonthRejectedAtBoundary() {
        Map<String, BigDecimal> prior = new ConcurrentHashMap<>();
        prior.put("MARRY", new BigDecimal("10"));

        ReportBatchReceipt rc = service.submitBatch(new ArrayList<>(Arrays.asList(
                r("B4", 1, "MARRY", "300"),
                r("B4", 2, "DIVORCE", "299"),
                r("B4", 3, "FIXCARD", "300"))), prior);

        TMarrReportRow marry = rows.stream().filter(x -> "B4".equals(x.getBatchNo()) && "MARRY".equals(x.getItemCode())).findFirst().get();
        TMarrReportRow divorce = rows.stream().filter(x -> "B4".equals(x.getBatchNo()) && "DIVORCE".equals(x.getItemCode())).findFirst().get();
        assertEquals(Integer.valueOf(2), marry.getStatus());
        assertTrue(marry.getRemark().contains("30 倍"));
        assertEquals(Integer.valueOf(1), divorce.getStatus(), "29倍不到门槛，照旧汇纳");
        assertNull(divorce.getRemark());
        // 无上月底数的项不查数十倍
        assertEquals(Integer.valueOf(1),
                rows.stream().filter(x -> "FIXCARD".equals(x.getItemCode())).findFirst().get().getStatus());
    }

    @Test
    @DisplayName("同一册号第二回只认头一遍：不盖先收行、不补缘故，回执标注重复")
    void duplicateBatchKeepsFirstSubmission() {
        service.submitBatch("B5", Arrays.asList(
                r("B5", 1, "MARRY", "12"),
                r("B5", 2, "DIVORCE", "-1")));
        TMarrReportRow firstBad = service.listErrors("B5").get(0);
        String firstRemark = firstBad.getRemark();

        ReportBatchReceipt second = submit("B5", Arrays.asList(
                r("B5", 1, "MARRY", "9999"),
                r("B5", 2, "DIVORCE", "8888")));

        assertTrue(second.isDuplicate());
        assertEquals(1, second.getAcceptedCount());
        assertEquals(1, second.getRejectedCount());
        assertEquals(2, service.listByBatch("B5").size(), "后遍一行都不许盖进来");
        assertEquals(firstRemark, service.listErrors("B5").get(0).getRemark(), "已退回行不许再补缘故");
        TMarrReportRow marry = service.listByBatch("B5").stream()
                .filter(x -> "MARRY".equals(x.getItemCode())).findFirst().get();
        assertEquals(0, new BigDecimal("12").compareTo(marry.getQty()), "头遍的数不许被盖掉");
    }

    @Test
    @DisplayName("越过上限整册不接、一行不落；老签名返回-1")
    void overflowRejectsWholeBatch() {
        List<TMarrReportRow> many = new ArrayList<>();
        for (int i = 1; i <= 61; i++) {
            many.add(r("B6", i, "ITEM" + i, "1"));
        }
        ReportBatchReceipt rc = service.submitBatch(many, null);
        assertEquals(ReportBatchReceipt.RESULT_OVERFLOW, rc.getResult());
        assertFalse(rc.isSealed());
        assertEquals(0, service.listByBatch("B6").size(), "一行不落");
        assertNull(service.getReceipt("B6"), "连册头都不落");

        List<TMarrReportRow> many2 = new ArrayList<>();
        for (int i = 1; i <= 61; i++) {
            many2.add(r("B7", i, "ITEM" + i, "1"));
        }
        assertEquals(-1, service.submitBatch("B7", many2), "原签名超限给 -1");
    }

    @Test
    @DisplayName("白册回执不出现核讫；同号再递认头一遍")
    void emptyBatchNeverSealed() {
        ReportBatchReceipt rc = submit("B8", new ArrayList<>());
        assertEquals(ReportBatchReceipt.RESULT_EMPTY, rc.getResult());
        assertFalse(rc.isSealed());
        assertFalse(rc.getMessage().contains("核讫"), "白册回执上不许出现核讫两个字");
        assertEquals(0, rc.getRawCount());
        assertTrue(rc.isBalanced());
        assertNotNull(service.getReceipt("B8"));

        ReportBatchReceipt again = submit("B8", new ArrayList<>());
        assertTrue(again.isDuplicate());
    }

    @Test
    @DisplayName("两县同月各一册：先后办、一齐办，账上同一个样子")
    void twoCountiesSameMonthSameResultRegardlessOfOrder() throws Exception {
        List<TMarrReportRow> c1 = Arrays.asList(r("C1", 1, "MARRY", "11"), r("C1", 2, "DIVORCE", "4"));
        List<TMarrReportRow> c2 = Arrays.asList(r("C2", 1, "MARRY", "22"), r("C2", 2, "DIVORCE", "-2"));

        // 一齐办：两个不同册号并发，互不串行不串数
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        pool.submit(() -> { await(start); service.submitBatch(c1, null); });
        pool.submit(() -> { await(start); service.submitBatch(c2, null); });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        ReportBatchReceipt r1 = service.getReceipt("C1");
        ReportBatchReceipt r2 = service.getReceipt("C2");
        assertEquals(2, r1.getAcceptedCount());
        assertEquals(0, r1.getRejectedCount());
        assertEquals(1, r2.getAcceptedCount());
        assertEquals(1, r2.getRejectedCount());
        assertTrue(r2.isBalanced());

        // 同册号并发重发：只能认进头一遍，行数不翻倍
        ExecutorService pool2 = Executors.newFixedThreadPool(2);
        CountDownLatch start2 = new CountDownLatch(1);
        pool2.submit(() -> { await(start2); service.submitBatch("D1",
                Arrays.asList(r("D1", 1, "MARRY", "1"), r("D1", 2, "DIVORCE", "2"))); });
        pool2.submit(() -> { await(start2); service.submitBatch("D1",
                Arrays.asList(r("D1", 1, "MARRY", "1"), r("D1", 2, "DIVORCE", "2"))); });
        start2.countDown();
        pool2.shutdown();
        assertTrue(pool2.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(2, service.listByBatch("D1").size(), "同号并发重发不得重复入账");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("满两个年度转史：收下行入史现行翻不着，退回行不动，册号仍可对")
    void archiveAfterTwoYears() {
        service.submitBatch("B9", Arrays.asList(
                r("B9", 1, "MARRY", "12"),
                r("B9", 2, "DIVORCE", "-3")));
        TMarrReportBatch head = heads.stream().filter(h -> "B9".equals(h.getBatchNo())).findFirst().get();
        Date threeYearsAgo = new Date(System.currentTimeMillis() - 3L * 365 * 24 * 3600 * 1000);
        head.setAcceptedAt(threeYearsAgo);

        int moved = service.archiveDue(new Date());
        assertEquals(1, moved);
        assertEquals(0, service.listByStatus(1).size(), "收下行现行页翻不着");
        assertEquals(1, service.listByStatus(2).size(), "退回行不转史，仍挂现行页");
        List<TMarrReportRowHis> archived = service.listHisByBatch("B9");
        assertEquals(1, archived.size());
        assertEquals("MARRY", archived.get(0).getItemCode());
        assertTrue(service.reconcile("B9"), "转史后册头与账上仍同口径：汇=现行汇+史");

        // 只凭册号在现行页拉不到收下行，凭册号在史册拉得到
        assertEquals(1, service.listByBatch("B9").size());
        assertEquals(1, service.listHisByItem("MARRY").size());

        // 再跑一回不重复挪
        assertEquals(0, service.archiveDue(new Date()));
    }

    @Test
    @DisplayName("转史两年边界：差一天不满两个年度不挪")
    void archiveBoundaryJustUnderTwoYearsStays() {
        service.submitBatch("B10", Arrays.asList(r("B10", 1, "MARRY", "12")));
        TMarrReportBatch head = heads.stream().filter(h -> "B10".equals(h.getBatchNo())).findFirst().get();
        head.setAcceptedAt(new Date(System.currentTimeMillis() - (2L * 365 - 1) * 24 * 3600 * 1000));

        service.archiveDue(new Date());
        assertEquals(1, service.listByBatch("B10").size(), "差一天不满两年，现行页还在");
        assertEquals(0, service.listHisByBatch("B10").size());
    }

    @Test
    @DisplayName("回执合计与库里合计同口径：册头落库数与重算数一致")
    void receiptMatchesLedgerTotals() {
        ReportBatchReceipt rc = submit("B11", Arrays.asList(
                r("B11", 1, "MARRY", "12"),
                r("B11", 2, "MARRY", "8"),
                r("B11", 3, "DIVORCE", "-1"),
                r("B11", 4, "FIXCARD", "30")));

        assertEquals(3, rc.getRawCount(), "并条后3行");
        assertTrue(rc.isBalanced());
        assertTrue(service.reconcile("B11"));

        ReportBatchReceipt loaded = service.getReceipt("B11");
        assertEquals(rc.getRawCount(), loaded.getRawCount());
        assertEquals(rc.getAcceptedCount(), loaded.getAcceptedCount());
        assertEquals(rc.getRejectedCount(), loaded.getRejectedCount());
        assertEquals(0, rc.getAcceptedQty().compareTo(loaded.getAcceptedQty()));
        // 翻账：凭报表项拉，现行页 MARRY 并条后只有1行
        assertEquals(1, service.listByItem("MARRY").size());
    }

    @Test
    @DisplayName("原签名继续可用：返回收下行数")
    void legacySignatureReturnsAcceptedCount() {
        int n = service.submitBatch("B12", Arrays.asList(
                r("B12", 1, "MARRY", "12"),
                r("B12", 2, "DIVORCE", "4")));
        assertEquals(2, n);
    }

    @Test
    @DisplayName("同项一行好一行负数：负数行按自己的原行次独立退回，不被并条加成洗没")
    void negativeHalfRejectedIndependentlyNotWashedByMerge() {
        ReportBatchReceipt rc = submit("B13", Arrays.asList(
                r("B13", 1, "MARRY", "10"),
                r("B13", 2, "MARRY", "-3")));

        assertEquals(2, rc.getRawCount());
        assertEquals(1, rc.getAcceptedCount());
        assertEquals(1, rc.getRejectedCount());
        assertTrue(rc.isBalanced());

        TMarrReportRow bad = rc.getRejectedRows().get(0);
        assertEquals(Integer.valueOf(2), bad.getRowNo(), "点得出歪的是原册第2行");
        assertTrue(bad.getRemark().contains("负数"));

        TMarrReportRow good = rows.stream()
                .filter(x -> "B13".equals(x.getBatchNo()) && x.getStatus() == 1).findFirst().get();
        assertEquals(0, new BigDecimal("10").compareTo(good.getQty()),
                "好的半行按 10 收，不许跟 -3 并成 7");
    }

    @Test
    @DisplayName("数十倍按并条后整月合计判：两行各自不到30倍、合计到30倍仍退回；行次留先出现的")
    void surgeJudgedOnMergedMonthlyTotal() {
        Map<String, BigDecimal> prior = new ConcurrentHashMap<>();
        prior.put("MARRY", new BigDecimal("10"));

        ReportBatchReceipt rc = service.submitBatch(new ArrayList<>(Arrays.asList(
                r("B14", 1, "MARRY", "150"),
                r("B14", 2, "MARRY", "160"))), prior);

        assertEquals(1, rc.getRawCount(), "同项并一条");
        assertEquals(0, rc.getAcceptedCount());
        assertEquals(1, rc.getRejectedCount());
        TMarrReportRow bad = rc.getRejectedRows().get(0);
        assertEquals(Integer.valueOf(1), bad.getRowNo(), "行次留先出现的第1行");
        assertEquals(0, new BigDecimal("310").compareTo(bad.getQty()), "退回的是整月合计 310");
        assertTrue(bad.getRemark().contains("30 倍"));
    }

    @Test
    @DisplayName("同项一行有对数一行空缺：空缺行独立退回，有对数行按原值收（不并成空或0）")
    void blankHalfRejectedValidHalfKept() {
        ReportBatchReceipt rc = submit("B15", Arrays.asList(
                r("B15", 1, "MARRY", "12"),
                r("B15", 2, "MARRY", null)));

        assertEquals(2, rc.getRawCount());
        assertEquals(1, rc.getAcceptedCount());
        assertEquals(1, rc.getRejectedCount());
        TMarrReportRow good = rows.stream()
                .filter(x -> "B15".equals(x.getBatchNo()) && x.getStatus() == 1).findFirst().get();
        assertEquals(0, new BigDecimal("12").compareTo(good.getQty()));
        assertTrue(rc.getRejectedRows().get(0).getRemark().contains("对数空缺"));
    }
}
