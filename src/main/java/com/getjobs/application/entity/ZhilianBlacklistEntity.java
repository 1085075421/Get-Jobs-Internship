package com.getjobs.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 智联招聘黑名单：公司或岗位名称关键词。 */
@Data
@TableName("zhilian_blacklist")
public class ZhilianBlacklistEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** company=公司名称，job=岗位名称。 */
    private String type;
    private String value;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
