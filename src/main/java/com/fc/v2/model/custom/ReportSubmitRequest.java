package com.fc.v2.model.custom;

import java.util.List;

import com.fc.v2.model.auto.TMarrReportBill;
import com.fc.v2.model.auto.TMarrReportRow;

/**
 * 登记统计报送册整册核收入参：一册头 + 原册各行，整册提交，不拆单行。
 *
 * @author fuce
 * @date 2026-09-28
 */
public class ReportSubmitRequest implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    /** 册头（册号、报送期、县区） */
    private TMarrReportBill bill;

    /** 原册各行（行次、报表项代号、对数照原册抄入，不许手工敲改） */
    private List<TMarrReportRow> rows;

    public TMarrReportBill getBill() {
        return bill;
    }

    public void setBill(TMarrReportBill bill) {
        this.bill = bill;
    }

    public List<TMarrReportRow> getRows() {
        return rows;
    }

    public void setRows(List<TMarrReportRow> rows) {
        this.rows = rows;
    }
}
