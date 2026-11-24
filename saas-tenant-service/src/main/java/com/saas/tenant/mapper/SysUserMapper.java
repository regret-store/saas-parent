package com.saas.tenant.mapper;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.saas.tenant.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

// 这里的 #tenant 会触发我们刚才写的 TenantDsProcessor
@DS("#tenant")
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}