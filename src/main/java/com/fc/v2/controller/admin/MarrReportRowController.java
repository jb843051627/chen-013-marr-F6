package com.fc.v2.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.domain.ResultTable;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.auto.TMarrReportRow;
import com.fc.v2.model.auto.TMarrReportRowHis;
import com.fc.v2.model.custom.ReportBatchReceipt;
import com.fc.v2.model.custom.ReportSubmitRequest;
import com.fc.v2.service.ITMarrReportRowService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 登记统计报送册核收 Controller（batch-process 形状：只有整册核收与只读翻账，无单行新增/就地改写）。
 *
 * @author fuce
 * @date 2026-09-29
 */
@Api(value = "登记统计报送册核收")
@Controller
@RequestMapping("/marrReport")
public class MarrReportRowController extends BaseController {

    private final String prefix = "admin/marrReport";

    @Autowired
    private ITMarrReportRowService marrReportRowService;

    @ApiOperation(value = "核收台账跳转", notes = "核收台账跳转")
    @GetMapping("/view")
    @RequiresPermissions("marr:reportRow:view")
    public String view(ModelMap model) {
        return prefix + "/list";
    }

    /**
     * 整册核收：业务服务层逐行处理的唯一入口。收几行、退几行以回执给回为准，页面不许自己数；
     * 白册/超上限/重发不认时 sealed=false，回执字样里不出现"核讫"。
     */
    @Log(title = "登记统计报送册整册核收", action = "submit")
    @ApiOperation(value = "整册核收", notes = "整册提交，逐行处理")
    @PostMapping("/submit")
    @RequiresPermissions("marr:reportRow:submit")
    @ResponseBody
    public AjaxResult submit(@RequestBody(required = false) ReportSubmitRequest request) {
        ReportBatchReceipt receipt;
        if (request == null) {
            // 连册子都没递进来，按白册给回执（不核讫）。
            receipt = marrReportRowService.submitBatch(Collections.<TMarrReportRow>emptyList());
        } else {
            List<TMarrReportRow> rows = request.getRows() == null
                    ? Collections.<TMarrReportRow>emptyList() : request.getRows();
            Map<String, BigDecimal> priorMonthQty = request.getPriorMonthQty();
            String batchNo = request.getBatchNo() == null ? null : request.getBatchNo().trim();
            if (batchNo != null && !batchNo.isEmpty()) {
                // 显式册号：盖到每一行（行里自带的号不作数，不许谁自己往上敲）。
                receipt = marrReportRowService.submitBatch(batchNo, rows, priorMonthQty);
            } else {
                // 册号取头一行所载。
                receipt = marrReportRowService.submitBatch(rows, priorMonthQty);
            }
        }
        return AjaxResult.successData(200, receipt);
    }

    @Log(title = "登记统计报送册现行台账", action = "list")
    @ApiOperation(value = "现行行台账", notes = "凭册号/报表项/行进展翻账，退回/收下各归各堆")
    @GetMapping("/list")
    @RequiresPermissions("marr:reportRow:list")
    @ResponseBody
    public ResultTable list(@RequestParam(required = false) String batchNo,
                            @RequestParam(required = false) String itemCode,
                            @RequestParam(required = false) Integer status) {
        QueryWrapper<TMarrReportRow> qw = new QueryWrapper<>();
        if (batchNo != null && !batchNo.trim().isEmpty()) {
            qw.eq("batch_no", batchNo.trim());
        }
        if (itemCode != null && !itemCode.trim().isEmpty()) {
            qw.eq("item_code", itemCode.trim());
        }
        if (status != null) {
            qw.eq("status", status);
        }
        qw.orderByAsc("batch_no", "row_no");
        startPage();
        com.github.pagehelper.PageInfo<TMarrReportRow> page =
                new com.github.pagehelper.PageInfo<>(marrReportRowService.selectList(qw));
        return pageTable(page.getList(), page.getTotal());
    }

    @Log(title = "登记统计报送册史册台账", action = "hisList")
    @ApiOperation(value = "史册行台账", notes = "现行页翻不着的收下行凭册号/报表项在此拉")
    @GetMapping("/hisList")
    @RequiresPermissions("marr:reportRow:list")
    @ResponseBody
    public ResultTable hisList(@RequestParam(required = false) String batchNo,
                               @RequestParam(required = false) String itemCode) {
        List<TMarrReportRowHis> data;
        if (batchNo != null && !batchNo.trim().isEmpty()) {
            data = marrReportRowService.listHisByBatch(batchNo.trim());
        } else if (itemCode != null && !itemCode.trim().isEmpty()) {
            data = marrReportRowService.listHisByItem(itemCode.trim());
        } else {
            data = Collections.emptyList();
        }
        return dataTable(data);
    }

    @ApiOperation(value = "册头回执", notes = "合计三数以核收当回落库者为准，挂+汇=原册行数")
    @GetMapping("/receipt")
    @RequiresPermissions("marr:reportRow:list")
    @ResponseBody
    public AjaxResult receipt(@RequestParam String batchNo) {
        ReportBatchReceipt receipt = marrReportRowService.getReceipt(batchNo.trim());
        if (receipt == null) {
            return AjaxResult.error("册号 " + batchNo + " 查无核收记录");
        }
        return AjaxResult.successData(200, receipt);
    }
}
