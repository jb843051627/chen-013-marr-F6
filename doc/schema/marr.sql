-- marr 婚姻登记与婚姻家庭辅导服务管理 -- schema (chen-013)
-- 列名与基线实体契约（@TableName/@TableField）逐列对齐，改列必须同步实体。
-- 库：chen_013

-- 登记统计报送册册头：一册一号，头一回核收时落账，重发一律认头一遍。
-- accepted/rejected/pending/archived 四个计数与 checked 标志，与行表在同一事务、同一回计算里写定，
-- 恒等式：汇纳 + 挂驳回 + 候着转史 + 已进史 = 原册行数（进史前 archived=0，即 挂着+汇纳=原册行数）。
-- 白册子 checked=0，回执不许出现“核讫”。
CREATE TABLE IF NOT EXISTS t_marr_report_bill (
  id bigint NOT NULL COMMENT '主键',
  batch_no varchar(64) NOT NULL COMMENT '登记统计报送册号',
  row_count int DEFAULT NULL COMMENT '原册行数(人头,合并条也按两行计)',
  accepted_count int DEFAULT NULL COMMENT '汇纳行数(已收下)',
  rejected_count int DEFAULT NULL COMMENT '挂驳回行数(退回)',
  pending_count int DEFAULT NULL COMMENT '候着转史行数',
  archived_count int DEFAULT '0' COMMENT '已挪入史册行数(人头)',
  checked_flag int DEFAULT '0' COMMENT '核讫标志 0未核讫(白册/重发) 1已核讫',
  reject_reason_summary varchar(500) DEFAULT NULL COMMENT '退回缘故清单(逐行,分号分隔)',
  period_year int DEFAULT NULL COMMENT '报送期年份',
  period_month int DEFAULT NULL COMMENT '报送期月份',
  county_name varchar(64) DEFAULT NULL COMMENT '报送县区名称',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '首次核收时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id),
  UNIQUE KEY uk_batch_no (batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='登记统计报送册册头';

-- 收下的行满两个年度挪进史册：现行页翻不着，只留册号可对。
CREATE TABLE IF NOT EXISTS t_marr_report_row_his (
  id bigint NOT NULL COMMENT '主键',
  batch_no varchar(64) DEFAULT NULL COMMENT '登记统计报送册号',
  row_no int DEFAULT NULL COMMENT '原册内行次',
  item_code varchar(64) DEFAULT NULL COMMENT '报表项代号',
  qty decimal(12,2) DEFAULT NULL COMMENT '本行填报登记对数(对)',
  merged_count int DEFAULT '1' COMMENT '本账条并记的原册行数(人头)',
  archive_time datetime DEFAULT NULL COMMENT '转史落档时间',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id),
  KEY idx_batch_no (batch_no),
  KEY idx_item_code (item_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='登记统计报送册核收转存行(史册)';

CREATE TABLE IF NOT EXISTS t_marr_cert_bill (
  id bigint NOT NULL COMMENT '主键',
  bill_no varchar(64) DEFAULT NULL COMMENT '婚姻登记记录证明核准单号',
  node_no int DEFAULT NULL COMMENT '当前所在层 0..3',
  sign_mode int DEFAULT NULL COMMENT '同层核准方式 0任一人 1两名点齐',
  need_count int DEFAULT NULL COMMENT '本层应签人数',
  sign_count int DEFAULT NULL COMMENT '本层已签人数',
  status int DEFAULT NULL COMMENT '报批情形 0在核 1已核讫 2已打回',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='婚姻登记记录证明逐级核准单';

CREATE TABLE IF NOT EXISTS t_marr_due_task (
  id bigint NOT NULL COMMENT '主键',
  item_no varchar(64) DEFAULT NULL COMMENT '到期催办事项单号',
  due_at datetime DEFAULT NULL COMMENT '本项届满之日那天的最后一刻',
  amount decimal(12,2) DEFAULT NULL COMMENT '单条提前催办的自然日数',
  content varchar(255) DEFAULT NULL COMMENT '催办事由与收信人记要',
  status int DEFAULT NULL COMMENT '条目情形 0待发 1已发 2发不出去',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='冷静期与领证期到期催办条目';

CREATE TABLE IF NOT EXISTS t_marr_fix_card (
  id bigint NOT NULL COMMENT '主键',
  biz_no varchar(64) DEFAULT NULL COMMENT '婚姻登记差错更正事务卡号',
  stage int DEFAULT NULL COMMENT '当前关口 0..5',
  status int DEFAULT NULL COMMENT '卡的落定 0在办 1已转出 2已结案',
  content varchar(255) DEFAULT NULL COMMENT '纠错记事',
  last_action varchar(64) DEFAULT NULL COMMENT '最近一次过口动作',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='婚姻登记差错更正事务卡';

CREATE TABLE IF NOT EXISTS t_marr_img_card (
  id bigint NOT NULL COMMENT '主键',
  bill_no varchar(64) DEFAULT NULL COMMENT '影像件建档卡号',
  node_no int DEFAULT NULL COMMENT '本件影像版本号',
  site_id int DEFAULT NULL COMMENT '所属卷宗基础档案',
  site_no varchar(64) DEFAULT NULL COMMENT '婚姻卷宗档案编号',
  qty decimal(12,2) DEFAULT NULL COMMENT '本卡合并后在册影像页数(页)',
  fine_amt decimal(12,2) DEFAULT NULL COMMENT '本年度新上件页数(页)',
  content varchar(255) DEFAULT NULL COMMENT '出证水印字样',
  grade_level int DEFAULT NULL COMMENT '影像完整度档',
  status int DEFAULT NULL COMMENT '进展 0待核对 1已核对 2已压死',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='卷宗影像件建档卡';

CREATE TABLE IF NOT EXISTS t_marr_report_row (
  id bigint NOT NULL COMMENT '主键',
  batch_no varchar(64) DEFAULT NULL COMMENT '登记统计报送册号',
  row_no int DEFAULT NULL COMMENT '原册内行次',
  item_code varchar(64) DEFAULT NULL COMMENT '报表项代号',
  qty decimal(12,2) DEFAULT NULL COMMENT '本行填报登记对数(对)',
  merged_count int DEFAULT '1' COMMENT '本账条并记的原册行数(人头,同报表项旧写法两行并一条时为2)',
  status int DEFAULT NULL COMMENT '行进展 0候着转史 1已汇纳(收下) 2挂驳回(退回)',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id),
  UNIQUE KEY uk_batch_rowno (batch_no, row_no),
  KEY idx_batch_no (batch_no),
  KEY idx_item_code (item_code),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='登记统计报送册核收转存行';

CREATE TABLE IF NOT EXISTS t_marr_risk_line (
  id bigint NOT NULL COMMENT '主键',
  rule_code varchar(64) DEFAULT NULL COMMENT '辅导风险判据代号',
  rule_name varchar(128) DEFAULT NULL COMMENT '判据名称',
  th1_max decimal(12,2) DEFAULT NULL COMMENT '一档风险分值封顶值',
  th2_max decimal(12,2) DEFAULT NULL COMMENT '二档风险分值封顶值',
  th3_max decimal(12,2) DEFAULT NULL COMMENT '三档风险分值封顶值',
  eff_start datetime DEFAULT NULL COMMENT '启用之日',
  eff_end datetime DEFAULT NULL COMMENT '交棒之日(不含)',
  priority int DEFAULT NULL COMMENT '抢档顺位(数值越大越优先)',
  status int DEFAULT NULL COMMENT '判据的情形 0现行 1已停用',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='婚姻家庭辅导风险评估判据线';

CREATE TABLE IF NOT EXISTS t_marr_vol_doc (
  id bigint NOT NULL COMMENT '主键',
  site_no varchar(64) DEFAULT NULL COMMENT '婚姻卷宗档案编号',
  site_name varchar(128) DEFAULT NULL COMMENT '卷宗基础档案名称',
  site_type varchar(32) DEFAULT NULL COMMENT '卷宗类别类',
  road_name varchar(128) DEFAULT NULL COMMENT '承办处(省—市—区县)',
  th1_max decimal(12,2) DEFAULT NULL COMMENT '在册影像页数一档上限(页)',
  th2_max decimal(12,2) DEFAULT NULL COMMENT '二档上限(页)',
  th3_max decimal(12,2) DEFAULT NULL COMMENT '三档上限(页)',
  status int DEFAULT NULL COMMENT '档案情形 0在库 1已封卷',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='婚姻卷宗基础档案';

-- 初始档案数据：预置两条卷宗基础档案，一条在册、一条已封卷。
-- id=0 为在册卷宗，可继续挂用；id=1 为已封卷卷宗，仅供翻查。
-- 在册影像页数上限按小、中、大三档取 5/12/30 页。
INSERT IGNORE INTO t_marr_vol_doc (id, site_no, site_name, site_type, road_name, th1_max, th2_max, th3_max, status, del_flag, create_by, create_time)
VALUES (0, 'MH00', '江原县民政局婚姻管理处结婚卷宗基础档案', '结婚', '江原省—江原市—临川县', 5.00, 12.00, 30.00, 0, 0, 'seed', NOW()),
       (1, 'MH01', '江原县民政局婚姻管理处离婚卷宗基础档案（已封卷）', '离婚', '江原省—江原市—临川县', 5.00, 12.00, 30.00, 1, 0, 'seed', NOW());

