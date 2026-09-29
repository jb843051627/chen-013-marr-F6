package com.fc.v2.model.auto;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;
import java.util.Date;

/**
 * 登记统计报送册册头对象 t_marr_report_bill
 * 一册一号，头一回核收落账；accepted + rejected + pending 三计数与核讫标志
 * 与行表同一回计算写定，重发只认头一遍。
 *
 * @author fuce
 * @date 2026-09-28
 */
@TableName("t_marr_report_bill")
@ApiModel(value = "TMarrReportBill", description = "登记统计报送册册头")
public class TMarrReportBill implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    @ApiModelProperty(value = "主键")
    private Long id;

    /** 登记统计报送册号 */
    @TableField("batch_no")
    @ApiModelProperty(value = "登记统计报送册号")
    private String batchNo;

    /** 原册行数(人头,合并条也按两行计) */
    @TableField("row_count")
    @ApiModelProperty(value = "原册行数(人头,合并条也按两行计)")
    private Integer rowCount;

    /** 汇纳行数(已收下) */
    @TableField("accepted_count")
    @ApiModelProperty(value = "汇纳行数(已收下)")
    private Integer acceptedCount;

    /** 挂驳回行数(退回) */
    @TableField("rejected_count")
    @ApiModelProperty(value = "挂驳回行数(退回)")
    private Integer rejectedCount;

    /** 候着转史行数 */
    @TableField("pending_count")
    @ApiModelProperty(value = "候着转史行数")
    private Integer pendingCount;

    /** 已挪入史册行数(人头) */
    @TableField("archived_count")
    @ApiModelProperty(value = "已挪入史册行数(人头)")
    private Integer archivedCount;

    /** 核讫标志 0未核讫(白册/重发) 1已核讫 */
    @TableField("checked_flag")
    @ApiModelProperty(value = "核讫标志 0未核讫(白册/重发) 1已核讫")
    private Integer checkedFlag;

    /** 退回缘故清单(逐行,分号分隔) */
    @TableField("reject_reason_summary")
    @ApiModelProperty(value = "退回缘故清单(逐行,分号分隔)")
    private String rejectReasonSummary;

    /** 报送期年份 */
    @TableField("period_year")
    @ApiModelProperty(value = "报送期年份")
    private Integer periodYear;

    /** 报送期月份 */
    @TableField("period_month")
    @ApiModelProperty(value = "报送期月份")
    private Integer periodMonth;

    /** 报送县区名称 */
    @TableField("county_name")
    @ApiModelProperty(value = "报送县区名称")
    private String countyName;

    /** 删除标记 0正常 1删除 */
    @TableField("del_flag")
    @ApiModelProperty(value = "删除标记 0正常 1删除")
    private Integer delFlag;

    /** 创建者 */
    @TableField(value = "create_by", fill = FieldFill.INSERT)
    @ApiModelProperty(value = "创建者")
    private String createBy;

    /** 首次核收时间 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "首次核收时间")
    private Date createTime;

    /** 更新者 */
    @TableField(value = "update_by", fill = FieldFill.UPDATE)
    @ApiModelProperty(value = "更新者")
    private String updateBy;

    /** 更新时间 */
    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "更新时间")
    private Date updateTime;

    /** 备注 */
    @TableField("remark")
    @ApiModelProperty(value = "备注")
    private String remark;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public Integer getRowCount() {
        return rowCount;
    }

    public void setRowCount(Integer rowCount) {
        this.rowCount = rowCount;
    }

    public Integer getAcceptedCount() {
        return acceptedCount;
    }

    public void setAcceptedCount(Integer acceptedCount) {
        this.acceptedCount = acceptedCount;
    }

    public Integer getRejectedCount() {
        return rejectedCount;
    }

    public void setRejectedCount(Integer rejectedCount) {
        this.rejectedCount = rejectedCount;
    }

    public Integer getPendingCount() {
        return pendingCount;
    }

    public void setPendingCount(Integer pendingCount) {
        this.pendingCount = pendingCount;
    }

    public Integer getArchivedCount() {
        return archivedCount;
    }

    public void setArchivedCount(Integer archivedCount) {
        this.archivedCount = archivedCount;
    }

    public Integer getCheckedFlag() {
        return checkedFlag;
    }

    public void setCheckedFlag(Integer checkedFlag) {
        this.checkedFlag = checkedFlag;
    }

    public String getRejectReasonSummary() {
        return rejectReasonSummary;
    }

    public void setRejectReasonSummary(String rejectReasonSummary) {
        this.rejectReasonSummary = rejectReasonSummary;
    }

    public Integer getPeriodYear() {
        return periodYear;
    }

    public void setPeriodYear(Integer periodYear) {
        this.periodYear = periodYear;
    }

    public Integer getPeriodMonth() {
        return periodMonth;
    }

    public void setPeriodMonth(Integer month) {
        this.periodMonth = month;
    }

    public String getCountyName() {
        return countyName;
    }

    public void setCountyName(String countyName) {
        this.countyName = countyName;
    }

    public Integer getDelFlag() {
        return delFlag;
    }

    public void setDelFlag(Integer delFlag) {
        this.delFlag = delFlag;
    }

    public String getCreateBy() {
        return createBy;
    }

    public void setCreateBy(String createBy) {
        this.createBy = createBy;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public String getUpdateBy() {
        return updateBy;
    }

    public void setUpdateBy(String updateBy) {
        this.updateBy = updateBy;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
