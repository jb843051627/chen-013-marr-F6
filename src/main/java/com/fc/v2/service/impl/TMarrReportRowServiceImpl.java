package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TMarrReportBillMapper;
import com.fc.v2.mapper.auto.TMarrReportRowHisMapper;
import com.fc.v2.mapper.auto.TMarrReportRowMapper;
import com.fc.v2.model.auto.TMarrReportBill;
import com.fc.v2.model.auto.TMarrReportRow;
import com.fc.v2.model.auto.TMarrReportRowHis;
import com.fc.v2.model.custom.ReportReceipt;
import com.fc.v2.service.ITMarrReportRowService;

/**
 * 登记统计报送册核收转存行 Service业务层处理（batch-process 形状：整册逐行核收，无单条增改）。
 *
 * 口径：
 * - 整册只从 {@link #submitBatch} 进来，一行一行过；收了几行、退回几行以本方法回执为准。
 * - Redis 按册号上锁，同号并发重发串行化，账上只认头一遍；Redis 不在场时退单机内锁。
 * - 超上限整册不接、一行不落；白册不落账、不置核讫。
 * - 同报表项多行目并一条（对数相加、人头并记、行次留先出现）。
 * - 计数全部在写库后的同一事务里回查库内实存得出，回执与库内同一回计算。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TMarrReportRowServiceImpl implements ITMarrReportRowService {

    /** 单册行数目上限（册子四十行上下，留余量） */
    static final int MAX_ROWS = 50;
    /** 较上月翻出几十倍的拦截倍数：超过 30 倍退本行 */
    static final BigDecimal SURGE_FACTOR = new BigDecimal(30);

    /** 行进展：0候着转史 1已汇纳(收下) 2挂驳回(退回) */
    static final int STATUS_PENDING = 0;
    static final int STATUS_OK = 1;
    static final int STATUS_FAIL = 2;

    private static final int DEL_OK = 0;
    private static final String LOCK_PREFIX = "marr:report:batchlock:";
    private static final long LOCK_TTL_SECONDS = 30L;
    private static final long LOCK_WAIT_MS = 5000L;

    @Autowired
    private TMarrReportRowMapper marrReportRowMapper;

    @Autowired
    private TMarrReportBillMapper marrReportBillMapper;

    @Autowired
    private TMarrReportRowHisMapper marrReportRowHisMapper;

    @Autowired(required = false)
    private RedisTemplate<Object, Object> redisTemplate;

    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    /** Redis 不在场时的单机兜底锁（按册号分条） */
    private final ConcurrentHashMap<String, ReentrantLock> localLocks = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // 整册核收
    // ------------------------------------------------------------------

    @Override
    public TMarrReportRow selectTMarrReportRowById(Long id) {
        return this.marrReportRowMapper.selectById(id);
    }

    @Override
    public int submitBatch(String batchNo, List<TMarrReportRow> rows) {
        TMarrReportBill billHead = new TMarrReportBill();
        billHead.setBatchNo(batchNo);
        ReportReceipt receipt = submitBatch(billHead, rows);
        if (receipt.isRejectedBill()) {
            return -1;
        }
        return receipt.getAccepted();
    }

    @Override
    public ReportReceipt submitBatch(final TMarrReportBill bill, final List<TMarrReportRow> rows) {
        if (bill == null || bill.getBatchNo() == null || bill.getBatchNo().trim().isEmpty()) {
            throw new IllegalArgumentException("册号不得空缺");
        }
        final String batchNo = bill.getBatchNo().trim();
        final List<TMarrReportRow> incoming = rows == null ? Collections.<TMarrReportRow>emptyList() : rows;

        return underBatchLock(batchNo, new java.util.concurrent.Callable<ReportReceipt>() {
            @Override
            public ReportReceipt call() {
                return executeInTx(new TransactionCallback<ReportReceipt>() {
                    @Override
                    public ReportReceipt doInTransaction(TransactionStatus status) {
                        return acceptBill(batchNo, bill, incoming);
                    }
                });
            }
        });
    }

    /** 锁内、事务内：整册逐行核收。 */
    private ReportReceipt acceptBill(String batchNo, TMarrReportBill bill, List<TMarrReportRow> incoming) {
        TMarrReportBill existed = marrReportBillMapper.selectOne(new QueryWrapper<TMarrReportBill>()
                .eq("batch_no", batchNo).eq("del_flag", DEL_OK));
        if (existed != null) {
            // 同一册号第二回递进来：账上只认头一遍，不盖先收的行，也不给退回行补缘故。
            return buildDuplicateReceipt(existed);
        }

        ReportReceipt receipt = new ReportReceipt();
        receipt.setBatchNo(batchNo);
        receipt.setTotal(incoming.size());

        // 越过上限：整册不接，一行不落，也不占册头。
        if (incoming.size() > MAX_ROWS) {
            receipt.setRejectedBill(true);
            return receipt;
        }
        // 白册子：不落账、不核讫，回执上不出现“核讫”。
        if (incoming.isEmpty()) {
            return receipt;
        }

        // 先落册头占住册号：同号并发在唯一索引上撞车时（如无 Redis 的多实例），
        // 撞了就转头一遍回执；此刻本事务一行未写，不会留下半册。
        TMarrReportBill head = new TMarrReportBill();
        head.setBatchNo(batchNo);
        head.setPeriodYear(bill.getPeriodYear());
        head.setPeriodMonth(bill.getPeriodMonth());
        head.setCountyName(bill.getCountyName());
        head.setDelFlag(DEL_OK);
        head.setCheckedFlag(1);
        try {
            marrReportBillMapper.insert(head);
        } catch (org.springframework.dao.DuplicateKeyException dke) {
            TMarrReportBill winner = marrReportBillMapper.selectOne(new QueryWrapper<TMarrReportBill>()
                    .eq("batch_no", batchNo).eq("del_flag", DEL_OK));
            if (winner == null) {
                throw new IllegalStateException("册号 " + batchNo + " 并发占坑冲突，请重发");
            }
            return buildDuplicateReceipt(winner);
        }

        // 逐行校验：非法行各退各的，缘故一句一事；合法行再按报表项并条。
        List<ReportReceipt.RejectedLine> rejectedLines = new ArrayList<>();
        Map<String, MergedLine> mergedByItem = new LinkedHashMap<>();

        for (TMarrReportRow raw : incoming) {
            Integer rowNo = raw == null ? null : raw.getRowNo();
            String itemCode = raw == null ? null
                    : (raw.getItemCode() == null ? null : raw.getItemCode().trim());
            BigDecimal qty = raw == null ? null : raw.getQty();

            String reason = validateLine(rowNo, itemCode, qty, batchNo, bill);
            if (reason != null) {
                TMarrReportRow failRow = newRow(batchNo, rowNo, itemCode, qty, 1, STATUS_FAIL, reason);
                marrReportRowMapper.insert(failRow);
                rejectedLines.add(new ReportReceipt.RejectedLine(rowNo, itemCode, qty, reason));
                continue;
            }

            MergedLine merged = mergedByItem.get(itemCode);
            if (merged == null) {
                merged = new MergedLine(rowNo, itemCode, qty);
                mergedByItem.put(itemCode, merged);
            } else {
                // 同项两个行目并一条：对数加起来只算一回，行次留先出现的，人头并记。
                merged.qty = merged.qty.add(qty);
                merged.heads++;
            }
        }

        for (MergedLine merged : mergedByItem.values()) {
            TMarrReportRow okRow = newRow(batchNo, merged.firstRowNo, merged.itemCode,
                    merged.qty, merged.heads, STATUS_OK, null);
            marrReportRowMapper.insert(okRow);
        }

        // 册头已先占坑；落账后以库内实存回算对齐（册头与回执同一回计算）。
        Counts counts = recountBill(head);
        int expectedHeads = incoming.size();
        if (counts.accepted + counts.rejected != expectedHeads || counts.pending != 0) {
            // 回执与库内对不上就整回掀掉，绝不带错数出门。
            throw new IllegalStateException("核收回算不平：册号=" + batchNo
                    + "，汇纳=" + counts.accepted + "，退回=" + counts.rejected
                    + "，原册=" + expectedHeads);
        }
        head.setRowCount(expectedHeads);
        head.setAcceptedCount(counts.accepted);
        head.setRejectedCount(counts.rejected);
        head.setPendingCount(0);
        head.setArchivedCount(0);
        head.setRejectReasonSummary(joinReasons(rejectedLines));
        marrReportBillMapper.updateById(head);

        receipt.setAccepted(counts.accepted);
        receipt.setRejected(counts.rejected);
        receipt.setPending(0);
        receipt.setChecked(true);
        Collections.sort(rejectedLines, new Comparator<ReportReceipt.RejectedLine>() {
            @Override
            public int compare(ReportReceipt.RejectedLine a, ReportReceipt.RejectedLine b) {
                int ra = a.getRowNo() == null ? Integer.MAX_VALUE : a.getRowNo();
                int rb = b.getRowNo() == null ? Integer.MAX_VALUE : b.getRowNo();
                return Integer.compare(ra, rb);
            }
        });
        receipt.setRejectedLines(rejectedLines);
        return receipt;
    }

    /** 逐行校验，返回退回缘故；合法返回 null。缘故一句话只讲一件事。 */
    private String validateLine(Integer rowNo, String itemCode, BigDecimal qty,
                                String batchNo, TMarrReportBill bill) {
        if (rowNo == null) {
            return "原册行次空缺，无法对回原册定位";
        }
        if (itemCode == null || itemCode.isEmpty()) {
            return "第" + rowNo + "行报表项代号空缺";
        }
        if (qty == null) {
            return "第" + rowNo + "行填报对数空缺";
        }
        if (qty.compareTo(BigDecimal.ZERO) < 0) {
            return "第" + rowNo + "行填报对数为负数(" + qty.toPlainString() + "对)";
        }
        if (qty.compareTo(BigDecimal.ZERO) == 0) {
            return "第" + rowNo + "行填报对数为0";
        }
        BigDecimal lastMonth = lastMonthQty(itemCode, batchNo, bill);
        if (lastMonth != null && lastMonth.compareTo(BigDecimal.ZERO) > 0
                && qty.compareTo(lastMonth.multiply(SURGE_FACTOR)) > 0) {
            return "第" + rowNo + "行对数" + qty.toPlainString() + "对较上月"
                    + lastMonth.toPlainString() + "对翻出30倍以上";
        }
        return null;
    }

    /** 取同报表项上期（上月）本县已汇纳对数，作激增比对。 */
    private BigDecimal lastMonthQty(String itemCode, String currentBatchNo, TMarrReportBill bill) {
        if (bill.getPeriodYear() == null || bill.getPeriodMonth() == null) {
            // 册头未带报送期时才退化为“最近一次汇纳”；带了报送期就只认本县上期，查无基数不拦。
            TMarrReportRow latest = marrReportRowMapper.selectOne(new QueryWrapper<TMarrReportRow>()
                    .eq("item_code", itemCode)
                    .ne("batch_no", currentBatchNo)
                    .eq("status", STATUS_OK)
                    .eq("del_flag", DEL_OK)
                    .orderByDesc("create_time")
                    .last("limit 1"));
            return latest == null ? null : latest.getQty();
        }
        Calendar cal = Calendar.getInstance();
        cal.set(bill.getPeriodYear(), bill.getPeriodMonth() - 1, 1);
        cal.add(Calendar.MONTH, -1);
        int prevYear = cal.get(Calendar.YEAR);
        int prevMonth = cal.get(Calendar.MONTH) + 1;

        QueryWrapper<TMarrReportBill> bw = new QueryWrapper<TMarrReportBill>()
                .eq("period_year", prevYear)
                .eq("period_month", prevMonth)
                .eq("del_flag", DEL_OK);
        if (bill.getCountyName() != null && !bill.getCountyName().trim().isEmpty()) {
            bw.eq("county_name", bill.getCountyName().trim());
        }
        bw.last("limit 1");
        TMarrReportBill prevBill = marrReportBillMapper.selectOne(bw);
        if (prevBill == null) {
            return null;
        }
        TMarrReportRow prev = marrReportRowMapper.selectOne(new QueryWrapper<TMarrReportRow>()
                .eq("batch_no", prevBill.getBatchNo())
                .eq("item_code", itemCode)
                .eq("status", STATUS_OK)
                .eq("del_flag", DEL_OK)
                .last("limit 1"));
        return prev == null ? null : prev.getQty();
    }

    // ------------------------------------------------------------------
    // 翻账
    // ------------------------------------------------------------------

    @Override
    public List<TMarrReportRow> listErrors(String batchNo) {
        return listCurrentByBatch(batchNo, STATUS_FAIL);
    }

    @Override
    public List<TMarrReportRow> listCurrentByBatch(String batchNo, Integer status) {
        QueryWrapper<TMarrReportRow> w = new QueryWrapper<TMarrReportRow>()
                .eq("batch_no", batchNo).eq("del_flag", DEL_OK)
                .orderByAsc("row_no");
        if (status != null) {
            w.eq("status", status);
        }
        return marrReportRowMapper.selectList(w);
    }

    @Override
    public List<TMarrReportRow> listCurrentByItem(String itemCode, Integer status) {
        QueryWrapper<TMarrReportRow> w = new QueryWrapper<TMarrReportRow>()
                .eq("item_code", itemCode).eq("del_flag", DEL_OK)
                .orderByAsc("batch_no").orderByAsc("row_no");
        if (status != null) {
            w.eq("status", status);
        }
        return marrReportRowMapper.selectList(w);
    }

    @Override
    public List<TMarrReportRowHis> listHistoryByBatch(String batchNo) {
        return marrReportRowHisMapper.selectList(new QueryWrapper<TMarrReportRowHis>()
                .eq("batch_no", batchNo).eq("del_flag", DEL_OK)
                .orderByAsc("row_no"));
    }

    @Override
    public List<TMarrReportRowHis> listHistoryByItem(String itemCode) {
        return marrReportRowHisMapper.selectList(new QueryWrapper<TMarrReportRowHis>()
                .eq("item_code", itemCode).eq("del_flag", DEL_OK)
                .orderByAsc("batch_no").orderByAsc("row_no"));
    }

    @Override
    public TMarrReportBill getBill(String batchNo) {
        return marrReportBillMapper.selectOne(new QueryWrapper<TMarrReportBill>()
                .eq("batch_no", batchNo).eq("del_flag", DEL_OK));
    }

    // ------------------------------------------------------------------
    // 满两个年度：挂候 → 挪史
    // ------------------------------------------------------------------

    @Override
    public int markMaturedForArchive() {
        return markMaturedForArchive(new Date());
    }

    @Override
    public int markMaturedForArchive(final Date asOf) {
        final Date cutoff = twoYearsBefore(asOf);
        Integer marked = executeInTx(new TransactionCallback<Integer>() {
            @Override
            public Integer doInTransaction(TransactionStatus status) {
                List<TMarrReportRow> accepted = marrReportRowMapper.selectList(
                        new QueryWrapper<TMarrReportRow>()
                                .eq("status", STATUS_OK).eq("del_flag", DEL_OK));
                Map<String, TMarrReportBill> billCache = new LinkedHashMap<>();
                int heads = 0;
                for (TMarrReportRow row : accepted) {
                    TMarrReportBill head = billCache.get(row.getBatchNo());
                    if (head == null) {
                        head = getBill(row.getBatchNo());
                        billCache.put(row.getBatchNo(), head);
                    }
                    if (head != null && isMatured(head, row, cutoff)) {
                        row.setStatus(STATUS_PENDING);
                        marrReportRowMapper.updateById(row);
                        heads += headsOf(row);
                    }
                }
                for (String batchNo : billCache.keySet()) {
                    refreshBillCounts(batchNo);
                }
                return heads;
            }
        });
        return marked == null ? 0 : marked;
    }

    @Override
    public int movePendingToHistory() {
        Integer moved = executeInTx(new TransactionCallback<Integer>() {
            @Override
            public Integer doInTransaction(TransactionStatus status) {
                List<TMarrReportRow> pending = marrReportRowMapper.selectList(
                        new QueryWrapper<TMarrReportRow>()
                                .eq("status", STATUS_PENDING).eq("del_flag", DEL_OK));
                if (pending.isEmpty()) {
                    return 0;
                }
                Date now = new Date();
                List<String> affectedBatches = new ArrayList<>();
                int heads = 0;
                for (TMarrReportRow row : pending) {
                    TMarrReportRowHis his = new TMarrReportRowHis();
                    his.setBatchNo(row.getBatchNo());
                    his.setRowNo(row.getRowNo());
                    his.setItemCode(row.getItemCode());
                    his.setQty(row.getQty());
                    his.setMergedCount(headsOf(row));
                    his.setArchiveTime(now);
                    his.setDelFlag(DEL_OK);
                    marrReportRowHisMapper.insert(his);
                    marrReportRowMapper.deleteById(row.getId());
                    heads += headsOf(row);
                    if (!affectedBatches.contains(row.getBatchNo())) {
                        affectedBatches.add(row.getBatchNo());
                    }
                }
                for (String batchNo : affectedBatches) {
                    refreshBillCounts(batchNo);
                }
                return heads;
            }
        });
        return moved == null ? 0 : moved;
    }

    @Override
    public int archiveMatured() {
        markMaturedForArchive();
        return movePendingToHistory();
    }

    /** 报送期（无报送期按收进时间）届满两个整年即满两个年度。 */
    private boolean isMatured(TMarrReportBill head, TMarrReportRow row, Date cutoff) {
        Date basis = null;
        if (head.getPeriodYear() != null && head.getPeriodMonth() != null) {
            Calendar cal = Calendar.getInstance();
            // 报送期月份最后一刻：取下月首日
            cal.set(head.getPeriodYear(), head.getPeriodMonth() - 1, 1, 0, 0, 0);
            cal.add(Calendar.MONTH, 1);
            basis = cal.getTime();
        } else if (row.getCreateTime() != null) {
            basis = row.getCreateTime();
        }
        return basis != null && !basis.after(cutoff);
    }

    private Date twoYearsBefore(Date asOf) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(asOf);
        cal.add(Calendar.YEAR, -2);
        return cal.getTime();
    }

    /** 以库内实存回算一册三堆人头并刷入册头；史册人头同样回算，保证两边同口径。 */
    private void refreshBillCounts(String batchNo) {
        TMarrReportBill head = getBill(batchNo);
        if (head == null) {
            return;
        }
        Counts current = recountBill(head);
        List<TMarrReportRowHis> hisRows = marrReportRowHisMapper.selectList(
                new QueryWrapper<TMarrReportRowHis>()
                        .eq("batch_no", batchNo).eq("del_flag", DEL_OK));
        int archived = 0;
        for (TMarrReportRowHis his : hisRows) {
            archived += his.getMergedCount() == null ? 1 : his.getMergedCount();
        }
        head.setAcceptedCount(current.accepted);
        head.setPendingCount(current.pending);
        head.setRejectedCount(current.rejected);
        head.setArchivedCount(archived);
        marrReportBillMapper.updateById(head);

        int total = current.accepted + current.pending + current.rejected + archived;
        if (head.getRowCount() != null && total != head.getRowCount()) {
            throw new IllegalStateException("转史回算不平：册号=" + batchNo
                    + "，现行+史册=" + total + "，原册=" + head.getRowCount());
        }
    }

    /** 同一回计算：直接从库内现行行表按进展堆点人头。 */
    private Counts recountBill(TMarrReportBill head) {
        List<TMarrReportRow> rows = marrReportRowMapper.selectList(
                new QueryWrapper<TMarrReportRow>()
                        .eq("batch_no", head.getBatchNo()).eq("del_flag", DEL_OK));
        Counts c = new Counts();
        for (TMarrReportRow r : rows) {
            int heads = headsOf(r);
            if (r.getStatus() == null) {
                continue;
            }
            switch (r.getStatus()) {
                case STATUS_OK:
                    c.accepted += heads;
                    break;
                case STATUS_FAIL:
                    c.rejected += heads;
                    break;
                case STATUS_PENDING:
                    c.pending += heads;
                    break;
                default:
                    break;
            }
        }
        return c;
    }

    // ------------------------------------------------------------------
    // 回执 / 工具
    // ------------------------------------------------------------------

    private ReportReceipt buildDuplicateReceipt(TMarrReportBill head) {
        ReportReceipt receipt = new ReportReceipt();
        receipt.setBatchNo(head.getBatchNo());
        receipt.setDuplicate(true);
        receipt.setTotal(head.getRowCount() == null ? 0 : head.getRowCount());
        receipt.setAccepted(head.getAcceptedCount() == null ? 0 : head.getAcceptedCount());
        receipt.setRejected(head.getRejectedCount() == null ? 0 : head.getRejectedCount());
        receipt.setPending(head.getPendingCount() == null ? 0 : head.getPendingCount());
        receipt.setArchived(head.getArchivedCount() == null ? 0 : head.getArchivedCount());
        receipt.setChecked(head.getCheckedFlag() != null && head.getCheckedFlag() == 1);
        for (TMarrReportRow fail : listErrors(head.getBatchNo())) {
            receipt.getRejectedLines().add(new ReportReceipt.RejectedLine(
                    fail.getRowNo(), fail.getItemCode(), fail.getQty(), fail.getRemark()));
        }
        return receipt;
    }

    private String joinReasons(List<ReportReceipt.RejectedLine> lines) {
        StringBuilder sb = new StringBuilder();
        for (ReportReceipt.RejectedLine line : lines) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(line.getReason());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private TMarrReportRow newRow(String batchNo, Integer rowNo, String itemCode,
                                  BigDecimal qty, int heads, int status, String reason) {
        TMarrReportRow row = new TMarrReportRow();
        row.setBatchNo(batchNo);
        row.setRowNo(rowNo);
        row.setItemCode(itemCode);
        row.setQty(qty);
        row.setMergedCount(heads);
        row.setStatus(status);
        row.setDelFlag(DEL_OK);
        row.setRemark(reason);
        return row;
    }

    private int headsOf(TMarrReportRow r) {
        return r.getMergedCount() == null || r.getMergedCount() < 1 ? 1 : r.getMergedCount();
    }

    private <T> T underBatchLock(String batchNo, java.util.concurrent.Callable<T> body) {
        if (redisTemplate != null) {
            return underRedisLock(batchNo, body);
        }
        ReentrantLock lock = localLocks.computeIfAbsent(batchNo, k -> new ReentrantLock());
        boolean locked = false;
        try {
            locked = lock.tryLock(LOCK_WAIT_MS, TimeUnit.MILLISECONDS);
            if (!locked) {
                throw new IllegalStateException("册号 " + batchNo + " 核收锁等待超时");
            }
            return body.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            if (locked) {
                lock.unlock();
            }
        }
    }

    private <T> T underRedisLock(String batchNo, java.util.concurrent.Callable<T> body) {
        String key = LOCK_PREFIX + batchNo;
        String token = Thread.currentThread().getName() + "@" + System.nanoTime();
        boolean locked = false;
        try {
            long deadline = System.currentTimeMillis() + LOCK_WAIT_MS;
            do {
                Boolean ok = redisTemplate.opsForValue().setIfAbsent(
                        key, token, LOCK_TTL_SECONDS, TimeUnit.SECONDS);
                if (Boolean.TRUE.equals(ok)) {
                    locked = true;
                    break;
                }
                Thread.sleep(50L);
            } while (System.currentTimeMillis() < deadline);
            if (!locked) {
                throw new IllegalStateException("册号 " + batchNo + " 核收锁等待超时");
            }
            return body.call();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            if (locked && token.equals(redisTemplate.opsForValue().get(key))) {
                redisTemplate.delete(key);
            }
        }
    }

    private <T> T executeInTx(TransactionCallback<T> action) {
        if (transactionManager == null) {
            return action.doInTransaction(new SimpleTransactionStatus());
        }
        return new TransactionTemplate(transactionManager).execute(action);
    }

    /** 同报表项并条中间态。 */
    private static final class MergedLine {
        private final Integer firstRowNo;
        private final String itemCode;
        private BigDecimal qty;
        private int heads;

        MergedLine(Integer firstRowNo, String itemCode, BigDecimal qty) {
            this.firstRowNo = firstRowNo;
            this.itemCode = itemCode;
            this.qty = qty;
            this.heads = 1;
        }
    }

    /** 现行三堆人头。 */
    private static final class Counts {
        private int accepted;
        private int rejected;
        private int pending;
    }
}
