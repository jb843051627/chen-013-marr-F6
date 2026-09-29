package com.fc.v2.controller.admin;

import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.auto.TMarrReportBill;
import com.fc.v2.model.custom.ReportReceipt;
import com.fc.v2.model.custom.ReportSubmitRequest;
import com.fc.v2.service.ITMarrReportRowService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 登记统计报送册核收 Controller（batch-process 形状）。
 * 只有整册核收与翻账查询入口，不提供单行新增/就地改写/删除——收了几行、退回几行
 * 一律以业务服务层逐行处理方法的回执为准，页面不自己数。
 *
 * @author fuce
 * @date 2026-09-28
 */
@Api(value = "登记统计报送册核收")
@Controller
@RequestMapping("/marrReportRow")
public class MarrReportRowController extends BaseController {

    @Autowired
    private ITMarrReportRowService marrReportRowService;

    @Log(title = "登记统计报送册整册核收", action = "submitBill")
    @ApiOperation(value = "整册核收（逐行处理，收退计数以服务层回执为准）", notes = "整册核收")
    @PostMapping("/submitBill")
    @RequiresPermissions("marr:marrReportRow:submit")
    @ResponseBody
    public AjaxResult submitBill(@RequestBody ReportSubmitRequest request) {
        if (request == null || request.getBill() == null) {
            return AjaxResult.error("册头与册号不得空缺");
        }
        ReportReceipt receipt = marrReportRowService.submitBatch(request.getBill(), request.getRows());
        return AjaxResult.success(200, receiptMessage(receipt), receipt);
    }

    @ApiOperation(value = "册头回执（计数以库内册头为准）", notes = "册头查询")
    @GetMapping("/bill")
    @RequiresPermissions("marr:marrReportRow:view")
    @ResponseBody
    public AjaxResult bill(String batchNo) {
        TMarrReportBill head = marrReportRowService.getBill(batchNo);
        if (head == null) {
            return AjaxResult.error("查无此册号");
        }
        return AjaxResult.successData(200, head);
    }

    @ApiOperation(value = "现行页翻账：按册号", notes = "按册号拉现行各行")
    @GetMapping("/currentByBatch")
    @RequiresPermissions("marr:marrReportRow:view")
    @ResponseBody
    public AjaxResult currentByBatch(String batchNo, Integer status) {
        return AjaxResult.successData(200, marrReportRowService.listCurrentByBatch(batchNo, status));
    }

    @ApiOperation(value = "现行页翻账：按报表项代号", notes = "按报表项拉现行各行")
    @GetMapping("/currentByItem")
    @RequiresPermissions("marr:marrReportRow:view")
    @ResponseBody
    public AjaxResult currentByItem(String itemCode, Integer status) {
        return AjaxResult.successData(200, marrReportRowService.listCurrentByItem(itemCode, status));
    }

    @ApiOperation(value = "退回行明细（按原册行次升序）", notes = "退回行查询")
    @GetMapping("/errors")
    @RequiresPermissions("marr:marrReportRow:view")
    @ResponseBody
    public AjaxResult errors(String batchNo) {
        return AjaxResult.successData(200, marrReportRowService.listErrors(batchNo));
    }

    @ApiOperation(value = "史册翻账：按册号", notes = "按册号拉史册")
    @GetMapping("/historyByBatch")
    @RequiresPermissions("marr:marrReportRow:view")
    @ResponseBody
    public AjaxResult historyByBatch(String batchNo) {
        return AjaxResult.successData(200, marrReportRowService.listHistoryByBatch(batchNo));
    }

    @ApiOperation(value = "史册翻账：按报表项代号", notes = "按报表项拉史册")
    @GetMapping("/historyByItem")
    @RequiresPermissions("marr:marrReportRow:view")
    @ResponseBody
    public AjaxResult historyByItem(String itemCode) {
        return AjaxResult.successData(200, marrReportRowService.listHistoryByItem(itemCode));
    }

    @Log(title = "登记统计报送册满两年度转史", action = "archive")
    @ApiOperation(value = "收下满两个年度的行挪入史册", notes = "转史")
    @PostMapping("/archiveMatured")
    @RequiresPermissions("marr:marrReportRow:archive")
    @ResponseBody
    public AjaxResult archiveMatured() {
        int moved = marrReportRowService.archiveMatured();
        return AjaxResult.success("本次挪入史册" + moved + "行");
    }

    /** 白册子回执严禁出现“核讫”字样，避免误判全市交齐。 */
    public static String receiptMessage(ReportReceipt r) {
        if (r.isRejectedBill()) {
            return "全册" + r.getTotal() + "行越过单册上限，整册不接、一行不落";
        }
        if (!r.isChecked()) {
            return "原册一行都没有，不具核收效力，请补送原册";
        }
        if (r.isDuplicate()) {
            return "该册号账上只认头一遍：汇纳" + r.getAccepted() + "行，退回" + r.getRejected() + "行";
        }
        return "核讫：原册" + r.getTotal() + "行，汇纳" + r.getAccepted()
                + "行，退回" + r.getRejected() + "行";
    }
}
