package com.aigroup.groupbuy.test.types.rule01.factory;

import com.aigroup.groupbuy.test.types.rule01.logic.RuleLogic101;
import com.aigroup.groupbuy.test.types.rule01.logic.RuleLogic102;
import com.aigroup.groupbuy.test.types.rule02.factory.Rule02TradeRuleFactory;
import cn.bugstack.wrench.design.framework.link.model1.ILogicLink;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

@Service
public class Rule01TradeRuleFactory {

    @Resource
    private RuleLogic101 ruleLogic101;
    @Resource
    private RuleLogic102 ruleLogic102;

    public ILogicLink<String, Rule02TradeRuleFactory.DynamicContext, String> openLogicLink() {
        ruleLogic101.appendNext(ruleLogic102);
        return ruleLogic101;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {
        private String age;
    }

}
