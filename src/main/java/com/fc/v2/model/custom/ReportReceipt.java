package com.fc.v2.model.custom;

import java.util.ArrayList;
import java.util.List;

/**
 * 登记统计报送册核收回执。
 * 计数与册头、行表出自同一回计算：accepted + rejected + pending = total。
 * checked=false 时一律不带“核讫”字样（白册子专用）。
 *
 * @author fuce
 * @date 2026-09-28
 */
public class ReportReceipt implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    /** 退回单行明细：缘故一句话只讲一件事，可直接定位原册行次 */
    public static class RejectedLine implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

        /** 原册内行次（合并行取先出现的行次） */
        private Integer rowNo;
        /** 报表项代号 */
        private String itemCode;
        /** 本行填报对数 */
        private java.math.BigDecimal qty;
        /** 退回缘故（一句一事，不写“数不对”） */
        private String reason;

        public RejectedLine() {
        }

        public RejectedLine(Integer rowNo, String itemCode, java.math.BigDecimal qty, String reason) {
            this.rowNo = rowNo;
            this.itemCode = itemCode;
            this.qty = qty;
            this.reason = reason;
        }

        public Integer getRowNo() {
            return rowNo;
        }

        public void setRowNo(Integer rowNo) {
            this.rowNo = rowNo;
        }

        public String getItemCode() {
            return itemCode;
        }

        public void setItemCode(String itemCode) {
            this.itemCode = itemCode;
        }

        public java.math.BigDecimal getQty() {
            return qty;
        }

        public void setQty(java.math.BigDecimal qty) {
            this.qty = qty;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }

    /** 册号 */
    private String batchNo;
    /** 原册行数（合并后入账行数；超上限整册不接时为提交原始行数） */
    private int total;
    /** 汇纳行数 */
    private int accepted;
    /** 挂驳回行数 */
    private int rejected;
    /** 候着转史行数（收下即按期龄挂候） */
    private int pending;
    /** 已挪入史册行数（人头） */
    private int archived;
    /** 是否核讫：false=白册/重发/整册不接，回执不得出现“核讫” */
    private boolean checked;
    /** 是否为同号重发（账上只认头一遍） */
    private boolean duplicate;
    /** 是否整册不接（越过行数上限，一行不落） */
    private boolean rejectedBill;
    /** 退回行明细，按原册行次升序 */
    private List<RejectedLine> rejectedLines = new ArrayList<>();

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getAccepted() {
        return accepted;
    }

    public void setAccepted(int accepted) {
        this.accepted = accepted;
    }

    public int getRejected() {
        return rejected;
    }

    public void setRejected(int rejected) {
        this.rejected = rejected;
    }

    public int getPending() {
        return pending;
    }

    public void setPending(int pending) {
        this.pending = pending;
    }

    public int getArchived() {
        return archived;
    }

    public void setArchived(int archived) {
        this.archived = archived;
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean checked) {
        this.checked = checked;
    }

    public boolean isDuplicate() {
        return duplicate;
    }

    public void setDuplicate(boolean duplicate) {
        this.duplicate = duplicate;
    }

    public boolean isRejectedBill() {
        return rejectedBill;
    }

    public void setRejectedBill(boolean rejectedBill) {
        this.rejectedBill = rejectedBill;
    }

    public List<RejectedLine> getRejectedLines() {
        return rejectedLines;
    }

    public void setRejectedLines(List<RejectedLine> rejectedLines) {
        this.rejectedLines = rejectedLines;
    }
}
