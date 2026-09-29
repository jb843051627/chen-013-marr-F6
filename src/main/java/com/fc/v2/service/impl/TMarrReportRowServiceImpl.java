package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TMarrReportBatchMapper;
import com.fc.v2.mapper.auto.TMarrReportRowHisMapper;
import com.fc.v2.mapper.auto.TMarrReportRowMapper;
import com.fc.v2.model.auto.TMarrReportBatch;
import com.fc.v2.model.auto.TMarrReportRow;
import com.fc.v2.model.auto.TMarrReportRowHis;
import com.fc.v2.model.custom.ReportBatchReceipt;
import com.fc.v2.service.ITMarrReportRowService;

/**
 * 登记统计报送册核收转存行 Service业务层处理（batch-process 形状：整册提交、逐行处理）。
 *
 * 规矩（一条不许走样）：
 * 1. 整册只从这里逐行进，不收单行手工新增/就地改写；收几行退几行以本类给回为准。
 * 2. 退回只退那一行：负数、空缺、较上月翻出数十倍等，一行挂驳回并写一句话具体缘故，其余各行照旧汇纳。
 * 3. 同册号第二回只认头一遍：册头 batch_no 唯一索引兜底，JVM 内按册号串行；后遍不盖先收行、不补缘故。
 * 4. 同册同报表项两行（上下半月旧写法）并一条：对数相加只算一回，行次留先出现的那个。
 * 5. 越过单册行数上限整册不接、一行不落；白册回执不出现"核讫"。
 * 6. 回执三数在核收当回一次算定：退回 + 收下 = 原册行数（并条后记账行数），册头与回执同口径同源。
 * 7. 收下的行满两个年度整行挪入史册，现行页翻不着，册头册号留对。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TMarrReportRowServiceImpl implements ITMarrReportRowService {

    /** 单册行数目上限：册里四十行上下，留宽到 60；越过整册不接、一行不落。 */
    private static final int MAX_ROWS = 60;

    /** 行进展 0候着转史（转史事务内瞬态） 1收下(已汇纳) 2退回(挂驳回) */
    private static final int STATUS_WAIT_ARCHIVE = 0;
    private static final int STATUS_ACCEPTED = 1;
    private static final int STATUS_REJECTED = 2;

    private static final int DEL_NORMAL = 0;

    /** 较上月翻出几十倍的门槛：达到 30 倍即整行退回（底数须为正数才作比较）。 */
    private static final BigDecimal SURGE_MULTIPLE = new BigDecimal("30");

    /** 收下的行届满几个年度挪史册。 */
    private static final int ARCHIVE_YEARS = 2;

    @javax.annotation.Resource
    private TMarrReportRowMapper marrReportRowMapper;

    @javax.annotation.Resource
    private TMarrReportBatchMapper marrReportBatchMapper;

    @javax.annotation.Resource
    private TMarrReportRowHisMapper marrReportRowHisMapper;

    /**
     * 同册号在本 JVM 内串行的门闩（基层重发、系统重发常事）；不同册号（两县同月）各走各门，
     * 先办后办、齐办结果一个样子。跨实例的最后一道兜底是册头表 batch_no 唯一索引。
     */
    private final ConcurrentHashMap<String, Object> batchLocks = new ConcurrentHashMap<>();

    private TransactionTemplate transactionTemplate;

    @Autowired(required = false)
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    /** 生产走事务（整段回滚，保证"一行不落"）；无容器的单测里直跑。 */
    private <T> T inTransaction(Supplier<T> work) {
        if (transactionTemplate == null) {
            return work.get();
        }
        return transactionTemplate.execute(status -> work.get());
    }

    private Object lockOf(String batchNo) {
        return batchLocks.computeIfAbsent(batchNo, k -> new Object());
    }

    @Override
    public TMarrReportRow selectTMarrReportRowById(Long id) {
        return this.marrReportRowMapper.selectById(id);
    }

    @Override
    public List<TMarrReportRow> selectList(QueryWrapper<TMarrReportRow> queryWrapper) {
        return this.marrReportRowMapper.selectList(queryWrapper);
    }

    @Override
    public int submitBatch(String batchNo, List<TMarrReportRow> rows) {
        ReportBatchReceipt receipt = submitBatch(batchNo, rows, null);
        return ReportBatchReceipt.RESULT_OVERFLOW == receipt.getResult() ? -1 : receipt.getAcceptedCount();
    }

    @Override
    public ReportBatchReceipt submitBatch(List<TMarrReportRow> rows) {
        return doSubmit(rows == null || rows.isEmpty() ? null : firstBatchNo(rows), rows, null);
    }

    @Override
    public ReportBatchReceipt submitBatch(List<TMarrReportRow> rows, Map<String, BigDecimal> priorMonthQty) {
        return doSubmit(rows == null || rows.isEmpty() ? null : firstBatchNo(rows), rows, priorMonthQty);
    }

    @Override
    public ReportBatchReceipt submitBatch(String batchNo, List<TMarrReportRow> rows,
                                         Map<String, BigDecimal> priorMonthQty) {
        // 册号以入参为准，盖到每一行（行里带的号不作数，不许谁自己敲）。
        return doSubmit(batchNo, rows == null ? null : normalizeCarry(batchNo, rows), priorMonthQty);
    }

    private ReportBatchReceipt doSubmit(String batchNo, List<TMarrReportRow> rows,
                                        Map<String, BigDecimal> priorMonthQty) {
        if (rows == null || rows.isEmpty()) {
            // 白册：一行都没有，回执不许出现"核讫"；有册号则落一册白册子头，同号再递只认这头一遍。
            return receiveEmpty(batchNo);
        }
        if (batchNo == null || batchNo.trim().isEmpty()) {
            throw new IllegalArgumentException("登记统计报送册号空缺，整册不接");
        }

        // 同册号先认头一遍：哪怕第二回的内容超了上限，也只把账上已认的头一遍端回去（改数走差异清单）。
        synchronized (lockOf(batchNo)) {
            TMarrReportBatch existing = findHead(batchNo);
            if (existing != null) {
                // 账上只认头一遍：后一遍不盖先收的行，也不给已退回的行补缘故。
                return loadReceipt(existing, true);
            }

            // 越过上限：进门就挡，整册不接、一行不落（连册头都不落）。
            if (rows.size() > MAX_ROWS) {
                ReportBatchReceipt r = new ReportBatchReceipt();
                r.setBatchNo(batchNo);
                r.setResult(ReportBatchReceipt.RESULT_OVERFLOW);
                r.setMessage("本行数 " + rows.size() + " 越过单册上限 " + MAX_ROWS + " 行，整册不接、一行不落");
                return r;
            }

            // 处理次序（不许颠倒）：原始行先逐行结构性定夺——歪的行保留自己的原册行次单独挂驳回，
            // 不能并条加和把负数/空缺掩盖掉；合法行之间再同项并一条；"翻几十倍"按并条后的整月合计判。
            // 全部在内存里算定，落库只是誊抄，回执三数与库里行出自同一回计算，没有"两下差一行"。
            List<TMarrReportRow> ledger = processRows(batchNo, rows, priorMonthQty);
            final ReportBatchReceipt receipt = buildReceipt(batchNo, ledger);

            try {
                inTransaction(() -> {
                    // 册头先落，batch_no 唯一索引即跨实例重发的兜底；插不进说明头一遍已在别处落定。
                    TMarrReportBatch head = newHead(batchNo, receipt);
                    marrReportBatchMapper.insert(head);
                    for (TMarrReportRow row : ledger) {
                        row.setId(null);
                        row.setDelFlag(DEL_NORMAL);
                        marrReportRowMapper.insert(row);
                    }
                    return null;
                });
            } catch (DuplicateKeyException dup) {
                // 同册号在另一实例抢先落了头一遍：整段已回滚，掉头认头遍给回。
                TMarrReportBatch winner = findHead(batchNo);
                if (winner == null) {
                    throw dup;
                }
                return loadReceipt(winner, true);
            }
            return receipt;
        }
    }

    /** 白册子头：result=2，三计数皆 0，不核讫。册号也空缺时只给回执、不落库（无号可对）。 */
    private ReportBatchReceipt receiveEmpty(String batchNo) {
        ReportBatchReceipt receipt = new ReportBatchReceipt();
        receipt.setBatchNo(batchNo);
        receipt.setResult(ReportBatchReceipt.RESULT_EMPTY);
        receipt.setAcceptedQty(BigDecimal.ZERO);
        receipt.setMessage("白册：册内一行都没有，不予验收盖讫");
        if (batchNo == null || batchNo.trim().isEmpty()) {
            return receipt;
        }
        synchronized (lockOf(batchNo)) {
            TMarrReportBatch existing = findHead(batchNo);
            if (existing != null) {
                return loadReceipt(existing, true);
            }
            try {
                inTransaction(() -> {
                    marrReportBatchMapper.insert(newHead(batchNo, receipt));
                    return null;
                });
            } catch (DuplicateKeyException dup) {
                TMarrReportBatch winner = findHead(batchNo);
                if (winner != null) {
                    return loadReceipt(winner, true);
                }
                throw dup;
            }
            return receipt;
        }
    }

    /**
     * 逐行进账两阶段，次序不许颠倒：
     * 一、在**原始行**上逐行做结构性定夺（行次空缺/重号、代号空缺、对数空缺/负数）。
     *    歪行保留自己的原册行次单独挂驳回——若先并条，一行 10、一行 -3 加成 7，
     *    负数那行的错就被加和洗没了，再也点不出是原册哪一行。
     * 二、活下来的合法行之间，同报表项并一条（对数相加只算一回，行次留先出现的那个）；
     *    "较上月翻几十倍"查的是整月口径，作用在并条之后的合计上。
     * 返回的就是账行：每条要么收下要么退回；rawCount=账行数，挂+汇天然等于它。
     */
    private List<TMarrReportRow> processRows(String batchNo, List<TMarrReportRow> rows,
                                             Map<String, BigDecimal> priorMonthQty) {
        List<TMarrReportRow> ledger = new ArrayList<>();
        List<TMarrReportRow> valid = new ArrayList<>();
        Map<Integer, Integer> firstRowSeenAt = new LinkedHashMap<>();

        int pos = 0;
        for (TMarrReportRow row : rows) {
            pos++;
            String reason = structuralReason(row, firstRowSeenAt, pos);
            row.setBatchNo(batchNo);
            if (reason != null) {
                row.setStatus(STATUS_REJECTED);
                row.setRemark(reason);
                ledger.add(row);
            } else {
                row.setItemCode(row.getItemCode().trim());
                valid.add(row);
            }
        }

        // 合法行同项并一条。进到这里的行对数必非空，直接相加。
        Map<String, TMarrReportRow> byItem = new LinkedHashMap<>();
        for (TMarrReportRow row : valid) {
            TMarrReportRow kept = byItem.get(row.getItemCode());
            if (kept == null) {
                byItem.put(row.getItemCode(), row);
            } else {
                kept.setQty(kept.getQty().add(row.getQty()));
            }
        }

        for (TMarrReportRow row : byItem.values()) {
            String surge = surgeReason(row, priorMonthQty);
            if (surge == null) {
                row.setStatus(STATUS_ACCEPTED);
            } else {
                row.setStatus(STATUS_REJECTED);
                row.setRemark(surge);
            }
            ledger.add(row);
        }
        return ledger;
    }

    /** 逐行结构性缘故：一句话只讲一件事（头一条撞着的为准），不许写"数不对"要人猜。 */
    private String structuralReason(TMarrReportRow row, Map<Integer, Integer> firstRowSeenAt, int pos) {
        if (row.getRowNo() == null) {
            return "原册行次空缺，无法对位原册，本行退回";
        }
        Integer firstAt = firstRowSeenAt.get(row.getRowNo());
        if (firstAt != null) {
            return "原册行次与第 " + firstAt + " 行重号，本行退回";
        }
        firstRowSeenAt.put(row.getRowNo(), pos);

        String code = row.getItemCode() == null ? "" : row.getItemCode().trim();
        if (code.isEmpty()) {
            return "报表项代号空缺，无法归项，本行退回";
        }

        BigDecimal qty = row.getQty();
        if (qty == null) {
            return "本行填报对数空缺，无数可汇，本行退回";
        }
        if (qty.signum() < 0) {
            return "本行填报对数为负数（" + qty.toPlainString() + " 对），本行退回";
        }
        return null;
    }

    /** 整月合计较上月翻出 30 倍及以上（底数须为正数、本月合计为正数才作比较）。 */
    private String surgeReason(TMarrReportRow row, Map<String, BigDecimal> priorMonthQty) {
        if (priorMonthQty == null) {
            return null;
        }
        BigDecimal prior = priorMonthQty.get(row.getItemCode());
        BigDecimal qty = row.getQty();
        if (prior != null && prior.signum() > 0 && qty.signum() > 0
                && qty.compareTo(prior.multiply(SURGE_MULTIPLE)) >= 0) {
            return "本行填报 " + qty.stripTrailingZeros().toPlainString() + " 对，较上月 "
                    + prior.stripTrailingZeros().toPlainString() + " 对翻出 30 倍以上，本行退回";
        }
        return null;
    }

    /** 三计数同一回算定：挂着的 + 汇纳的 = 原册行数（并条后记账行数）。 */
    private ReportBatchReceipt buildReceipt(String batchNo, List<TMarrReportRow> merged) {
        ReportBatchReceipt receipt = new ReportBatchReceipt();
        receipt.setBatchNo(batchNo);
        receipt.setRawCount(merged.size());
        BigDecimal acceptedQty = BigDecimal.ZERO;
        List<TMarrReportRow> rejected = new ArrayList<>();
        for (TMarrReportRow row : merged) {
            if (row.getStatus() == STATUS_REJECTED) {
                rejected.add(row);
            } else {
                receipt.setAcceptedCount(receipt.getAcceptedCount() + 1);
                if (row.getQty() != null) {
                    acceptedQty = acceptedQty.add(row.getQty());
                }
            }
        }
        rejected.sort(Comparator.comparing(TMarrReportRow::getRowNo,
                Comparator.nullsLast(Comparator.naturalOrder())));
        receipt.setRejectedRows(rejected);
        receipt.setRejectedCount(rejected.size());
        receipt.setAcceptedQty(acceptedQty);
        if (merged.isEmpty()) {
            receipt.setResult(ReportBatchReceipt.RESULT_EMPTY);
        } else if (!rejected.isEmpty()) {
            receipt.setResult(ReportBatchReceipt.RESULT_PARTIAL);
            receipt.setMessage("部分退回：" + rejected.size() + " 行挂驳回，其余 "
                    + receipt.getAcceptedCount() + " 行照旧汇纳");
        } else {
            receipt.setResult(ReportBatchReceipt.RESULT_CLOSED);
            receipt.setMessage("核讫：" + merged.size() + " 行全部汇纳");
        }
        return receipt;
    }

    private TMarrReportBatch newHead(String batchNo, ReportBatchReceipt receipt) {
        TMarrReportBatch head = new TMarrReportBatch();
        head.setBatchNo(batchNo);
        head.setRawCount(receipt.getRawCount());
        head.setAcceptedCount(receipt.getAcceptedCount());
        head.setRejectedCount(receipt.getRejectedCount());
        head.setAcceptedQty(receipt.getAcceptedQty() == null ? BigDecimal.ZERO : receipt.getAcceptedQty());
        head.setResult(receipt.getResult());
        head.setAcceptedAt(new Date());
        head.setDelFlag(DEL_NORMAL);
        return head;
    }

    private TMarrReportBatch findHead(String batchNo) {
        return marrReportBatchMapper.selectOne(new QueryWrapper<TMarrReportBatch>()
                .eq("batch_no", batchNo).eq("del_flag", DEL_NORMAL));
    }

    /** 认头遍给回：册头三数与退回行原样端出，不重算、不改写、不补缘故。 */
    private ReportBatchReceipt loadReceipt(TMarrReportBatch head, boolean duplicate) {
        ReportBatchReceipt receipt = new ReportBatchReceipt();
        receipt.setBatchNo(head.getBatchNo());
        receipt.setResult(head.getResult() == null ? ReportBatchReceipt.RESULT_PARTIAL : head.getResult());
        receipt.setDuplicate(duplicate);
        receipt.setRawCount(head.getRawCount() == null ? 0 : head.getRawCount());
        receipt.setAcceptedCount(head.getAcceptedCount() == null ? 0 : head.getAcceptedCount());
        receipt.setRejectedCount(head.getRejectedCount() == null ? 0 : head.getRejectedCount());
        receipt.setAcceptedQty(head.getAcceptedQty() == null ? BigDecimal.ZERO : head.getAcceptedQty());
        receipt.setRejectedRows(listErrors(head.getBatchNo()));
        receipt.setMessage(duplicate ? "同一册号第二回递送，账上只认头一遍，此回未入账" : null);
        return receipt;
    }

    private List<TMarrReportRow> normalizeCarry(String batchNo, List<TMarrReportRow> rows) {
        List<TMarrReportRow> copy = new ArrayList<>(rows);
        for (TMarrReportRow row : copy) {
            row.setBatchNo(batchNo);
        }
        return copy;
    }

    private String firstBatchNo(List<TMarrReportRow> rows) {
        String no = rows.get(0).getBatchNo();
        return no == null ? null : no.trim();
    }

    @Override
    public List<TMarrReportRow> listErrors(String batchNo) {
        return marrReportRowMapper.selectList(new QueryWrapper<TMarrReportRow>()
                .eq("batch_no", batchNo)
                .eq("del_flag", DEL_NORMAL)
                .eq("status", STATUS_REJECTED)
                .orderByAsc("row_no"));
    }

    @Override
    public List<TMarrReportRow> listByBatch(String batchNo) {
        return marrReportRowMapper.selectList(new QueryWrapper<TMarrReportRow>()
                .eq("batch_no", batchNo).eq("del_flag", DEL_NORMAL).orderByAsc("row_no"));
    }

    @Override
    public List<TMarrReportRow> listByItem(String itemCode) {
        return marrReportRowMapper.selectList(new QueryWrapper<TMarrReportRow>()
                .eq("item_code", itemCode).eq("del_flag", DEL_NORMAL)
                .orderByAsc("batch_no", "row_no"));
    }

    @Override
    public List<TMarrReportRow> listByStatus(int status) {
        return marrReportRowMapper.selectList(new QueryWrapper<TMarrReportRow>()
                .eq("status", status).eq("del_flag", DEL_NORMAL)
                .orderByAsc("batch_no", "row_no"));
    }

    @Override
    public List<TMarrReportRowHis> listHisByBatch(String batchNo) {
        return marrReportRowHisMapper.selectList(new QueryWrapper<TMarrReportRowHis>()
                .eq("batch_no", batchNo).orderByAsc("row_no"));
    }

    @Override
    public List<TMarrReportRowHis> listHisByItem(String itemCode) {
        return marrReportRowHisMapper.selectList(new QueryWrapper<TMarrReportRowHis>()
                .eq("item_code", itemCode).orderByAsc("batch_no", "row_no"));
    }

    @Override
    public ReportBatchReceipt getReceipt(String batchNo) {
        TMarrReportBatch head = findHead(batchNo);
        return head == null ? null : loadReceipt(head, false);
    }

    @Override
    public int archiveDue(Date asOf) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(asOf == null ? new Date() : asOf);
        calendar.add(Calendar.YEAR, -ARCHIVE_YEARS);
        Date cutoff = calendar.getTime();

        // 只翻有收下行、且头遍核收届满两个年度的册；退回行不动，仍挂现行页候差异清单。
        List<TMarrReportBatch> heads = marrReportBatchMapper.selectList(new QueryWrapper<TMarrReportBatch>()
                .eq("del_flag", DEL_NORMAL)
                .in("result", ReportBatchReceipt.RESULT_CLOSED, ReportBatchReceipt.RESULT_PARTIAL)
                .le("accepted_at", cutoff));
        int moved = 0;
        for (TMarrReportBatch head : heads) {
            // status=0 是上轮崩在半道留下的"候着转史"残行，一并捞起续挪，不重收、不丢行。
            List<TMarrReportRow> due = marrReportRowMapper.selectList(new QueryWrapper<TMarrReportRow>()
                    .eq("batch_no", head.getBatchNo())
                    .eq("del_flag", DEL_NORMAL)
                    .in("status", STATUS_WAIT_ARCHIVE, STATUS_ACCEPTED));
            if (due.isEmpty()) {
                continue;
            }
            // 先离汇纳堆、挂"候着转史"：这一刻起现行页按收下拉不到它，史册尚未落；
            // 崩在此后下轮凭 0 态接着挪。
            inTransaction(() -> {
                for (TMarrReportRow row : due) {
                    if (row.getStatus() == STATUS_ACCEPTED) {
                        row.setStatus(STATUS_WAIT_ARCHIVE);
                        marrReportRowMapper.updateById(row);
                    }
                }
                return null;
            });
            final Date stamp = asOf == null ? new Date() : asOf;
            final List<TMarrReportRow> toMove = due;
            // 再整行誊入史册并从现行页删除；入史与删除同一事务，留要么都在要么都不在。
            inTransaction(() -> {
                for (TMarrReportRow row : toMove) {
                    marrReportRowHisMapper.insert(toHis(row, stamp));
                    marrReportRowMapper.deleteById(row.getId());
                }
                return null;
            });
            moved += due.size();
        }
        return moved;
    }

    private TMarrReportRowHis toHis(TMarrReportRow row, Date archivedAt) {
        TMarrReportRowHis his = new TMarrReportRowHis();
        his.setId(row.getId());
        his.setBatchNo(row.getBatchNo());
        his.setRowNo(row.getRowNo());
        his.setItemCode(row.getItemCode());
        his.setQty(row.getQty());
        his.setStatus(STATUS_ACCEPTED);
        his.setArchivedAt(archivedAt);
        his.setCreateBy(row.getCreateBy());
        his.setCreateTime(row.getCreateTime());
        his.setUpdateBy(row.getUpdateBy());
        his.setUpdateTime(row.getUpdateTime());
        his.setRemark(row.getRemark());
        return his;
    }

    /**
     * 同口径核对：收下行 = 现行页收下行 + 史册行（转史后现行页翻不着）；退回行不转史。
     * 册头落库的三数必须与账上重算一致，且 挂 + 汇 = 原册行数。
     */
    @Override
    public boolean reconcile(String batchNo) {
        TMarrReportBatch head = findHead(batchNo);
        if (head == null) {
            return false;
        }
        // 收下这堆含转史半道挂起的 0 态残行，挪动期间恒等式也不闪断。
        long currentAccepted = marrReportRowMapper.selectCount(new QueryWrapper<TMarrReportRow>()
                .eq("batch_no", batchNo).eq("del_flag", DEL_NORMAL)
                .in("status", STATUS_WAIT_ARCHIVE, STATUS_ACCEPTED));
        long currentRejected = marrReportRowMapper.selectCount(new QueryWrapper<TMarrReportRow>()
                .eq("batch_no", batchNo).eq("del_flag", DEL_NORMAL).eq("status", STATUS_REJECTED));
        long hisAccepted = marrReportRowHisMapper.selectCount(new QueryWrapper<TMarrReportRowHis>()
                .eq("batch_no", batchNo));
        Integer hRaw = head.getRawCount();
        Integer hAccepted = head.getAcceptedCount();
        Integer hRejected = head.getRejectedCount();
        return hRaw != null && hAccepted != null && hRejected != null
                && hAccepted + hRejected == hRaw
                && hAccepted == currentAccepted + hisAccepted
                && hRejected == currentRejected;
    }
}
