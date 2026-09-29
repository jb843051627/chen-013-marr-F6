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
import java.math.BigDecimal;
import java.util.Date;

/**
 * 登记统计报送册册头回执对象 t_marr_report_batch
 *
 * @author fuce
 * @date 2026-09-29
 */
@TableName("t_marr_report_batch")
@ApiModel(value = "TMarrReportBatch", description = "登记统计报送册册头回执")
public class TMarrReportBatch implements Serializable {
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

    /** 原册行数(同项并条后的记账行数) */
    @TableField("raw_count")
    @ApiModelProperty(value = "原册行数(同项并条后的记账行数)")
    private Integer rawCount;

    /** 收下(已汇纳)行数 */
    @TableField("accepted_count")
    @ApiModelProperty(value = "收下(已汇纳)行数")
    private Integer acceptedCount;

    /** 退回(挂驳回)行数 */
    @TableField("rejected_count")
    @ApiModelProperty(value = "退回(挂驳回)行数")
    private Integer rejectedCount;

    /** 收下各行对数合计(对) */
    @TableField("accepted_qty")
    @ApiModelProperty(value = "收下各行对数合计(对)")
    private BigDecimal acceptedQty;

    /** 回执情形 0核讫 1部分退回 2白册(3超上限只在回执上给、不落册头) */
    @TableField("result")
    @ApiModelProperty(value = "回执情形 0核讫 1部分退回 2白册")
    private Integer result;

    /** 头遍核收时间(满两个年度转史以此起算) */
    @TableField("accepted_at")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "头遍核收时间")
    private Date acceptedAt;

    /** 删除标记 0正常 1删除 */
    @TableField("del_flag")
    @ApiModelProperty(value = "删除标记 0正常 1删除")
    private Integer delFlag;

    /** 创建者 */
    @TableField(value = "create_by", fill = FieldFill.INSERT)
    @ApiModelProperty(value = "创建者")
    private String createBy;

    /** 创建时间 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "创建时间")
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

    public Integer getRawCount() {
        return rawCount;
    }

    public void setRawCount(Integer rawCount) {
        this.rawCount = rawCount;
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

    public BigDecimal getAcceptedQty() {
        return acceptedQty;
    }

    public void setAcceptedQty(BigDecimal acceptedQty) {
        this.acceptedQty = acceptedQty;
    }

    public Integer getResult() {
        return result;
    }

    public void setResult(Integer result) {
        this.result = result;
    }

    public Date getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(Date acceptedAt) {
        this.acceptedAt = acceptedAt;
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
