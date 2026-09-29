package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fc.v2.controller.admin.MarrReportRowController;
import com.fc.v2.mapper.auto.TMarrReportBillMapper;
import com.fc.v2.mapper.auto.TMarrReportRowHisMapper;
import com.fc.v2.mapper.auto.TMarrReportRowMapper;
import com.fc.v2.model.auto.TMarrReportBill;
import com.fc.v2.model.auto.TMarrReportRow;
import com.fc.v2.model.auto.TMarrReportRowHis;
import com.fc.v2.model.custom.ReportReceipt;

/**
 * 登记统计报送册核收规矩的逐条验证（不起 Spring，内存假库 + Mockito）。
 *
 * @author fuce
 * @date 2026-09-28
 */
public class TMarrReportRowServiceImplTest {

    private static final int OK = 1;
    private static final int FAIL = 2;
    private static final int PENDING = 0;

    private TMarrReportRowServiceImpl service;
    private FakeDb db;

    @BeforeEach
    public void setUp() {
        service = new TMarrReportRowServiceImpl();
        db = new FakeDb();
        TMarrReportRowMapper rowMapper = mock(TMarrReportRowMapper.class);
        TMarrReportBillMapper billMapper = mock(TMarrReportBillMapper.class);
        TMarrReportRowHisMapper hisMapper = mock(TMarrReportRowHisMapper.class);
        db.bind(rowMapper, billMapper, hisMapper);
        ReflectionTestUtils.setField(service, "marrReportRowMapper", rowMapper);
        ReflectionTestUtils.setField(service, "marrReportBillMapper", billMapper);
        ReflectionTestUtils.setField(service, "marrReportRowHisMapper", hisMapper);
        // redisTemplate / transactionManager 留空：走单机锁 + 直通“事务”
    }

    private TMarrReportRow row(int rowNo, String item, String qty) {
        TMarrReportRow r = new TMarrReportRow();
        r.setRowNo(rowNo);
        r.setItemCode(item);
        r.setQty(qty == null ? null : new BigDecimal(qty));
        return r;
    }

    private TMarrReportBill bill(String no, Integer year, Integer month, String county) {
        TMarrReportBill b = new TMarrReportBill();
        b.setBatchNo(no);
        b.setPeriodYear(year);
        b.setPeriodMonth(month);
        b.setCountyName(county);
        return b;
    }

    private List<TMarrReportRow> rows(int n) {
        List<TMarrReportRow> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(row(i, "XM" + i, "10"));
        }
        return list;
    }

    // 规矩：一行歪了只退那一行，其余照旧入账；缘故能定位行次；计数两边同一口径
    @Test
    public void testOneBadLineOnlyThatLineRejected() {
        List<TMarrReportRow> rows = rows(40);
        rows.get(6).setQty(new BigDecimal("-5")); // 原册第7行多写/写歪

        ReportReceipt receipt = service.submitBatch(bill("B001", 2026, 9, "临川县"), rows);

        assertEquals(40, receipt.getTotal());
        assertEquals(39, receipt.getAccepted());
        assertEquals(1, receipt.getRejected());
        assertTrue(receipt.isChecked());
        assertFalse(receipt.isDuplicate());
        assertEquals(1, receipt.getRejectedLines().size());
        ReportReceipt.RejectedLine bad = receipt.getRejectedLines().get(0);
        assertEquals(Integer.valueOf(7), bad.getRowNo());
        assertTrue(bad.getReason().contains("第7行"), "缘故须指到行次: " + bad.getReason());
        assertTrue(bad.getReason().contains("-5"), "缘故须讲清一件事: " + bad.getReason());
        assertFalse(bad.getReason().contains("数不对"));

        List<TMarrReportRow> stored = service.listCurrentByBatch("B001", null);
        assertEquals(40, stored.size());
        assertEquals(1, service.listErrors("B001").size());
        TMarrReportBill head = service.getBill("B001");
        assertEquals(Integer.valueOf(40), head.getRowCount());
        assertEquals(Integer.valueOf(39), head.getAcceptedCount());
        assertEquals(Integer.valueOf(1), head.getRejectedCount());
        assertEquals(Integer.valueOf(0), head.getPendingCount());
        // 挂着(退回) + 汇纳 = 原册行数
        assertEquals(40, head.getAcceptedCount() + head.getRejectedCount());
    }

    // 规矩：缘故一句一事，覆盖空缺/0/负数/较上期激增
    @Test
    public void testSpecificReasonsAndSurge() {
        // 上期：临川县 2026-08，JH=10 对
        service.submitBatch(bill("PREV", 2026, 8, "临川县"),
                Collections.singletonList(row(1, "JH", "10")));

        List<TMarrReportRow> cur = new ArrayList<>();
        cur.add(row(1, "JH", "301"));   // 较上月 10 翻出30倍（>300）→ 退
        cur.add(row(2, "JH2", "300")); // 无上期基数，不拦
        cur.add(row(3, "", "8"));      // 代号空缺
        cur.add(row(4, "LH", "0"));    // 0
        cur.add(row(5, "NH", null));   // 对数空缺
        TMarrReportRow noRowNo = row(0, "PH", "8");
        noRowNo.setRowNo(null);
        cur.add(noRowNo);

        ReportReceipt receipt = service.submitBatch(bill("CUR", 2026, 9, "临川县"), cur);

        assertEquals(1, receipt.getAccepted());
        assertEquals(5, receipt.getRejected());
        Map<Integer, String> reasons = new LinkedHashMap<>();
        for (ReportReceipt.RejectedLine l : receipt.getRejectedLines()) {
            reasons.put(l.getRowNo(), l.getReason());
        }
        assertTrue(reasons.get(1).contains("30倍"), reasons.get(1));
        assertTrue(reasons.get(3).contains("代号空缺"));
        assertTrue(reasons.get(4).contains("为0"));
        assertTrue(reasons.get(5).contains("对数空缺"));
        assertTrue(reasons.get(null).contains("行次空缺"));
    }

    // 规矩：同册号第二回只认头一遍——不盖先收的行，不给退回行补缘故
    @Test
    public void testDuplicateBatchKeepsFirstReceipt() {
        List<TMarrReportRow> first = rows(3);
        first.get(1).setQty(new BigDecimal("-1")); // 第2行退回
        ReportReceipt r1 = service.submitBatch(bill("DUP", 2026, 9, "临川县"), first);
        assertEquals(2, r1.getAccepted());

        // 基层拿不准重发：数改“对”了，还多带一行
        List<TMarrReportRow> second = rows(5);
        ReportReceipt r2 = service.submitBatch(bill("DUP", 2026, 9, "临川县"), second);

        assertTrue(r2.isDuplicate());
        assertEquals(2, r2.getAccepted());
        assertEquals(1, r2.getRejected());
        assertEquals(3, r2.getTotal());
        assertEquals(3, service.listCurrentByBatch("DUP", null).size());
        assertEquals(1, service.listErrors("DUP").size());
        assertTrue(service.listErrors("DUP").get(0).getRemark().contains("第2行"));
    }

    // 规矩：同报表项两个行目并一条，对数相加只算一回，行次留先出现的，人头算两个
    @Test
    public void testMergeSameItemKeepsFirstRowNoAndAddsQty() {
        List<TMarrReportRow> rows = new ArrayList<>();
        rows.add(row(5, "JH", "3"));
        rows.add(row(6, "JH", "4"));
        rows.add(row(7, "LH", "9"));

        ReportReceipt receipt = service.submitBatch(bill("MRG", 2026, 9, "临川县"), rows);

        assertEquals(3, receipt.getTotal());
        assertEquals(3, receipt.getAccepted());
        assertEquals(0, receipt.getRejected());
        List<TMarrReportRow> stored = service.listCurrentByBatch("MRG", OK);
        assertEquals(2, stored.size());
        TMarrReportRow merged = stored.get(0).getRowNo() == 5 ? stored.get(0) : stored.get(1);
        assertEquals(Integer.valueOf(5), merged.getRowNo());
        assertEquals(0, new BigDecimal("7").compareTo(merged.getQty()));
        assertEquals(Integer.valueOf(2), merged.getMergedCount());
    }

    // 规矩：白册子一行不落账，回执/文案不许出现“核讫”
    @Test
    public void testEmptyBillNotChecked() {
        ReportReceipt receipt = service.submitBatch(bill("EMP", 2026, 9, "临川县"),
                new ArrayList<TMarrReportRow>());
        assertFalse(receipt.isChecked());
        assertEquals(0, receipt.getTotal());
        assertNull(service.getBill("EMP"));
        assertEquals(0, service.listCurrentByBatch("EMP", null).size());
        assertFalse(MarrReportRowController.receiptMessage(receipt).contains("核讫"));

        ReportReceipt receiptNull = service.submitBatch(bill("EMP2", 2026, 9, "临川县"), null);
        assertFalse(receiptNull.isChecked());
        assertNull(service.getBill("EMP2"));
    }

    // 规矩：越过上限整册不接、一行不落；原签名返回 -1
    @Test
    public void testOverLimitWholeBillRejected() {
        List<TMarrReportRow> tooMany = rows(TMarrReportRowServiceImpl.MAX_ROWS + 1);
        ReportReceipt receipt = service.submitBatch(bill("BIG", 2026, 9, "临川县"), tooMany);
        assertTrue(receipt.isRejectedBill());
        assertEquals(0, service.listCurrentByBatch("BIG", null).size());
        assertNull(service.getBill("BIG"));

        int code = service.submitBatch("BIG2", rows(TMarrReportRowServiceImpl.MAX_ROWS + 1));
        assertEquals(-1, code);
        assertNull(service.getBill("BIG2"));

        // 上限内照常核收
        ReportReceipt edge = service.submitBatch(bill("OKMAX", 2026, 9, "临川县"),
                rows(TMarrReportRowServiceImpl.MAX_ROWS));
        assertTrue(edge.isChecked());
        assertEquals(TMarrReportRowServiceImpl.MAX_ROWS, edge.getAccepted());
        assertEquals(TMarrReportRowServiceImpl.MAX_ROWS,
                service.submitBatch("OKMAX2", rows(TMarrReportRowServiceImpl.MAX_ROWS)));
    }

    // 规矩：两个县区同月各一册，一先一后办，账上各自完整
    @Test
    public void testTwoCountiesSameMonthSequential() {
        ReportReceipt a = service.submitBatch(bill("C-A", 2026, 9, "甲县"), rows(40));
        ReportReceipt b = service.submitBatch(bill("C-B", 2026, 9, "乙县"), rows(38));
        assertTrue(a.isChecked() && b.isChecked());
        assertEquals(40, service.listCurrentByBatch("C-A", null).size());
        assertEquals(38, service.listCurrentByBatch("C-B", null).size());
    }

    // 规矩：两边一齐办（并发），不同册号各自落账；同册号并发只有头一遍落账
    @Test
    public void testConcurrentSubmissions() throws InterruptedException {
        final CountDownLatch start = new CountDownLatch(1);
        final List<ReportReceipt> diffResults = new ArrayList<>();
        Thread t1 = new Thread(new Runnable() {
            public void run() {
                await(start);
                diffResults.add(service.submitBatch(bill("CC-A", 2026, 9, "甲县"), rows(40)));
            }
        });
        Thread t2 = new Thread(new Runnable() {
            public void run() {
                await(start);
                diffResults.add(service.submitBatch(bill("CC-B", 2026, 9, "乙县"), rows(40)));
            }
        });
        t1.start();
        t2.start();
        start.countDown();
        t1.join();
        t2.join();
        assertEquals(2, diffResults.size());
        for (ReportReceipt r : diffResults) {
            assertTrue(r.isChecked());
            assertEquals(40, r.getAccepted());
        }
        assertEquals(40, service.listCurrentByBatch("CC-A", null).size());
        assertEquals(40, service.listCurrentByBatch("CC-B", null).size());

        // 同册号并发重发：恰一册头，行不翻倍
        final List<ReportReceipt> sameResults = Collections.synchronizedList(new ArrayList<ReportReceipt>());
        final CountDownLatch start2 = new CountDownLatch(1);
        Runnable sameTask = new Runnable() {
            public void run() {
                await(start2);
                sameResults.add(service.submitBatch(bill("CC-SAME", 2026, 9, "甲县"), rows(10)));
            }
        };
        Thread g1 = new Thread(sameTask);
        Thread g2 = new Thread(sameTask);
        g1.start();
        g2.start();
        start2.countDown();
        g1.join();
        g2.join();
        assertEquals(2, sameResults.size());
        int dupes = 0;
        for (ReportReceipt r : sameResults) {
            if (r.isDuplicate()) {
                dupes++;
            }
        }
        assertEquals(1, dupes);
        assertNotNull(service.getBill("CC-SAME"));
        assertEquals(10, service.listCurrentByBatch("CC-SAME", null).size());
    }

    // 规矩：收下满两个年度先挂候着转史，再挪入史册；现行页翻不着，只留册号可对；账始终平
    @Test
    public void testMaturedRowsGoToHistory() {
        // 老册 2024-08：2 收 1 退
        List<TMarrReportRow> oldRows = rows(3);
        oldRows.get(2).setQty(new BigDecimal("-9"));
        service.submitBatch(bill("OLD", 2024, 8, "临川县"), oldRows);
        // 新册 2026-08：1 收
        service.submitBatch(bill("NEW", 2026, 8, "临川县"),
                Collections.singletonList(row(1, "JH", "5")));

        Calendar cal = Calendar.getInstance();
        cal.set(2026, Calendar.SEPTEMBER, 28, 0, 0, 0);
        Date asOf = cal.getTime();

        int marked = service.markMaturedForArchive(asOf);
        assertEquals(2, marked);
        assertEquals(2, service.listCurrentByBatch("OLD", PENDING).size());
        assertEquals(0, service.listCurrentByBatch("OLD", OK).size());
        assertEquals(1, service.listCurrentByBatch("OLD", FAIL).size());
        assertEquals(1, service.listCurrentByBatch("NEW", OK).size());
        TMarrReportBill oldHead = service.getBill("OLD");
        assertEquals(Integer.valueOf(2), oldHead.getPendingCount());
        assertEquals(Integer.valueOf(0), oldHead.getArchivedCount());

        int moved = service.movePendingToHistory();
        assertEquals(2, moved);
        // 现行页翻不着老册收下的行
        assertEquals(0, service.listCurrentByBatch("OLD", PENDING).size());
        assertEquals(0, service.listCurrentByBatch("OLD", OK).size());
        assertEquals(1, service.listCurrentByBatch("OLD", null).size()); // 退回行还在
        // 凭册号还拉得出史册
        List<TMarrReportRowHis> his = service.listHistoryByBatch("OLD");
        assertEquals(2, his.size());
        for (TMarrReportRowHis h : his) {
            assertNotNull(h.getArchiveTime());
            assertTrue(h.getRowNo() >= 1 && h.getRowNo() <= 2);
        }
        assertNotNull(service.listHistoryByItem("XM1"));
        oldHead = service.getBill("OLD");
        assertEquals(Integer.valueOf(2), oldHead.getArchivedCount());
        assertEquals(Integer.valueOf(0), oldHead.getPendingCount());
        // 汇纳(0) + 挂驳回(1) + 候着(0) + 进史(2) = 原册3
        assertEquals(3, oldHead.getAcceptedCount() + oldHead.getRejectedCount()
                + oldHead.getPendingCount() + oldHead.getArchivedCount());
        // 新册未届两年，原封不动
        assertEquals(1, service.listCurrentByBatch("NEW", OK).size());
        assertEquals(0, service.listHistoryByBatch("NEW").size());

        // 一气做完的入口同样可用（此时无新增届期行）
        assertEquals(0, service.archiveMatured());
    }

    // 规矩：按报表项翻账，退回/收下/进史各归各堆
    @Test
    public void testQueryByItemAndPiles() {
        List<TMarrReportRow> rows = rows(5);
        rows.get(0).setItemCode("SHARED");
        rows.get(1).setItemCode("SHARED"); // 合并到一条
        rows.get(2).setQty(new BigDecimal("-3"));
        service.submitBatch(bill("Q", 2026, 9, "临川县"), rows);

        List<TMarrReportRow> shared = service.listCurrentByItem("SHARED", OK);
        assertEquals(1, shared.size());
        assertEquals(0, new BigDecimal("20").compareTo(shared.get(0).getQty()));
        assertEquals(1, service.listCurrentByItem("XM3", FAIL).size());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // 内存假库：把三个 BaseMapper 的 selectList/selectOne/insert/updateById/deleteById
    // 接到内存 List，Wrapper 里的 eq/ne 条件简单解析后过滤。
    // ------------------------------------------------------------------
    private static class FakeDb {
        private final List<TMarrReportRow> rows = Collections.synchronizedList(new ArrayList<TMarrReportRow>());
        private final List<TMarrReportBill> bills = Collections.synchronizedList(new ArrayList<TMarrReportBill>());
        private final List<TMarrReportRowHis> his = Collections.synchronizedList(new ArrayList<TMarrReportRowHis>());
        private final AtomicLong ids = new AtomicLong(1000);

        void bind(TMarrReportRowMapper rm, TMarrReportBillMapper bm, TMarrReportRowHisMapper hm) {
            when(rm.insert(any(TMarrReportRow.class))).thenAnswer(inv -> {
                TMarrReportRow r = inv.getArgument(0);
                r.setId(ids.incrementAndGet());
                rows.add(r);
                return 1;
            });
            when(rm.selectList(any())).thenAnswer(inv -> filterRows(inv.getArgument(0)));
            when(rm.selectOne(any())).thenAnswer(inv -> {
                List<TMarrReportRow> l = filterRows(inv.getArgument(0));
                return l.isEmpty() ? null : l.get(0);
            });
            when(rm.selectById(any())).thenAnswer(inv -> {
                Long id = Long.valueOf(inv.getArgument(0).toString());
                synchronized (rows) {
                    for (TMarrReportRow r : rows) {
                        if (id.equals(r.getId())) {
                            return r;
                        }
                    }
                }
                return null;
            });
            when(rm.updateById(any())).thenAnswer(inv -> {
                TMarrReportRow arg = inv.getArgument(0);
                synchronized (rows) {
                    for (int i = 0; i < rows.size(); i++) {
                        if (rows.get(i).getId().equals(arg.getId())) {
                            if (arg.getStatus() != null) {
                                rows.get(i).setStatus(arg.getStatus());
                            }
                            return 1;
                        }
                    }
                }
                return 0;
            });
            when(rm.deleteById(any())).thenAnswer(inv -> {
                Long id = Long.valueOf(inv.getArgument(0).toString());
                synchronized (rows) {
                    rows.removeIf(r -> id.equals(r.getId()));
                }
                return 1;
            });

            when(bm.insert(any(TMarrReportBill.class))).thenAnswer(inv -> {
                TMarrReportBill b = inv.getArgument(0);
                b.setId(ids.incrementAndGet());
                bills.add(b);
                return 1;
            });
            when(bm.selectList(any())).thenAnswer(inv -> filterBills(inv.getArgument(0)));
            when(bm.selectOne(any())).thenAnswer(inv -> {
                List<TMarrReportBill> l = filterBills(inv.getArgument(0));
                return l.isEmpty() ? null : l.get(0);
            });
            when(bm.selectById(any())).thenAnswer(inv -> {
                Long id = Long.valueOf(inv.getArgument(0).toString());
                synchronized (bills) {
                    for (TMarrReportBill b : bills) {
                        if (id.equals(b.getId())) {
                            return b;
                        }
                    }
                }
                return null;
            });
            when(bm.updateById(any())).thenAnswer(inv -> {
                TMarrReportBill arg = inv.getArgument(0);
                synchronized (bills) {
                    for (TMarrReportBill b : bills) {
                        if (b.getId().equals(arg.getId())) {
                            b.setRowCount(arg.getRowCount());
                            b.setAcceptedCount(arg.getAcceptedCount());
                            b.setRejectedCount(arg.getRejectedCount());
                            b.setPendingCount(arg.getPendingCount());
                            b.setArchivedCount(arg.getArchivedCount());
                            b.setRejectReasonSummary(arg.getRejectReasonSummary());
                            b.setCheckedFlag(arg.getCheckedFlag());
                            return 1;
                        }
                    }
                }
                return 0;
            });

            when(hm.insert(any(TMarrReportRowHis.class))).thenAnswer(inv -> {
                TMarrReportRowHis h = inv.getArgument(0);
                h.setId(ids.incrementAndGet());
                his.add(h);
                return 1;
            });
            when(hm.selectList(any())).thenAnswer(inv -> filterHis(inv.getArgument(0)));
        }

        private List<TMarrReportRow> filterRows(Wrapper<?> w) {
            Map<String, Object> cond = conditions(w);
            List<TMarrReportRow> out = new ArrayList<>();
            synchronized (rows) {
                for (TMarrReportRow r : rows) {
                    if (match(cond, "batch_no", r.getBatchNo())
                            && match(cond, "item_code", r.getItemCode())
                            && match(cond, "status", r.getStatus())
                            && match(cond, "del_flag", r.getDelFlag())
                            && matchNe(cond, "batch_no", r.getBatchNo())) {
                        out.add(r);
                    }
                }
            }
            sortRows(out, w);
            return out;
        }

        private List<TMarrReportBill> filterBills(Wrapper<?> w) {
            Map<String, Object> cond = conditions(w);
            List<TMarrReportBill> out = new ArrayList<>();
            synchronized (bills) {
                for (TMarrReportBill b : bills) {
                    if (match(cond, "batch_no", b.getBatchNo())
                            && match(cond, "period_year", b.getPeriodYear())
                            && match(cond, "period_month", b.getPeriodMonth())
                            && match(cond, "county_name", b.getCountyName())
                            && match(cond, "del_flag", b.getDelFlag())) {
                        out.add(b);
                    }
                }
            }
            return out;
        }

        private List<TMarrReportRowHis> filterHis(Wrapper<?> w) {
            Map<String, Object> cond = conditions(w);
            List<TMarrReportRowHis> out = new ArrayList<>();
            synchronized (his) {
                for (TMarrReportRowHis h : his) {
                    if (match(cond, "batch_no", h.getBatchNo())
                            && match(cond, "item_code", h.getItemCode())
                            && match(cond, "del_flag", h.getDelFlag())) {
                        out.add(h);
                    }
                }
            }
            return out;
        }
    }

    // ---- Wrapper 条件解析（只用于内存假库） ----
    private static final Pattern COND_PATTERN =
            Pattern.compile("(\\w+)\\s*(=|<>|!=)\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)}");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> conditions(Wrapper<?> w) {
        Map<String, Object> raw = ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) w)
                .getParamNameValuePairs();
        String sql = w.getSqlSegment() == null ? "" : w.getSqlSegment();
        Map<String, Object> ordered = new LinkedHashMap<>();
        Matcher m = COND_PATTERN.matcher(sql);
        while (m.find()) {
            String column = m.group(1);
            String op = m.group(2);
            String key = m.group(3);
            ordered.put((op.equals("=") ? "" : "ne:") + column, raw.get(key));
        }
        return ordered;
    }

    private static boolean match(Map<String, Object> cond, String col, Object val) {
        Object expect = cond.get(col);
        if (expect == null) {
            return true;
        }
        if (val == null) {
            return false;
        }
        return expect.toString().equals(val.toString());
    }

    private static boolean matchNe(Map<String, Object> cond, String col, Object val) {
        Object expect = cond.get("ne:" + col);
        return expect == null || !expect.toString().equals(val == null ? null : val.toString());
    }

    @SuppressWarnings("unchecked")
    private static void sortRows(List<TMarrReportRow> out, Wrapper<?> w) {
        String sql = w.getSqlSegment() == null ? "" : w.getSqlSegment();
        if (sql.toUpperCase().contains("ROW_NO ASC")) {
            Collections.sort(out, (a, b) -> {
                int ra = a.getRowNo() == null ? Integer.MAX_VALUE : a.getRowNo();
                int rb = b.getRowNo() == null ? Integer.MAX_VALUE : b.getRowNo();
                return Integer.compare(ra, rb);
            });
        }
    }
}
