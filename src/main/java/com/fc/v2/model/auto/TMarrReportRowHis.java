package com.fc.v2.model.auto;

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
 * 登记统计报送册核收转存行(史册)对象 t_marr_report_row_his
 *
 * @author fuce
 * @date 2026-09-29
 */
@TableName("t_marr_report_row_his")
@ApiModel(value = "TMarrReportRowHis", description = "登记统计报送册核收转存行(史册)")
public class TMarrReportRowHis implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 主键(沿用原现行行主键) */
    @TableId(type = IdType.INPUT)
    @JsonSerialize(using = ToStringSerializer.class)
    @ApiModelProperty(value = "主键(沿用原现行行主键)")
    private Long id;

    /** 登记统计报送册号 */
    @TableField("batch_no")
    @ApiModelProperty(value = "登记统计报送册号")
    private String batchNo;

    /** 原册内行次 */
    @TableField("row_no")
    @ApiModelProperty(value = "原册内行次")
    private Integer rowNo;

    /** 报表项代号 */
    @TableField("item_code")
    @ApiModelProperty(value = "报表项代号")
    private String itemCode;

    /** 本行填报登记对数(对) */
    @TableField("qty")
    @ApiModelProperty(value = "本行填报登记对数(对)")
    private BigDecimal qty;

    /** 行进展(入史时为1收下) */
    @TableField("status")
    @ApiModelProperty(value = "行进展(入史时为1收下)")
    private Integer status;

    /** 挪进史册时间 */
    @TableField("archived_at")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "挪进史册时间")
    private Date archivedAt;

    /** 创建者 */
    @TableField("create_by")
    @ApiModelProperty(value = "创建者")
    private String createBy;

    /** 创建时间(原核收时间) */
    @TableField("create_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "创建时间(原核收时间)")
    private Date createTime;

    /** 更新者 */
    @TableField("update_by")
    @ApiModelProperty(value = "更新者")
    private String updateBy;

    /** 更新时间 */
    @TableField("update_time")
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

    public BigDecimal getQty() {
        return qty;
    }

    public void setQty(BigDecimal qty) {
        this.qty = qty;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Date getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(Date archivedAt) {
        this.archivedAt = archivedAt;
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
