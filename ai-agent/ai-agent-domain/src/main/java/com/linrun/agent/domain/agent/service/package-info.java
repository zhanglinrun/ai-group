/**
 * 历史兼容 service 目录。
 * 领域服务负责装配、调度、执行与会话规则；HTTP/SSE 请求适配留在 trigger。
 * 删除时机：当 case/runtime 新模型完全替代现有执行策略与装配节点后，逐步清空本目录。
 */
package com.linrun.agent.domain.agent.service;
