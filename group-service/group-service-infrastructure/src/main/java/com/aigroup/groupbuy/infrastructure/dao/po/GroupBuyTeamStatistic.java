package com.aigroup.groupbuy.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class GroupBuyTeamStatistic {

    private Long allTeamCount;
    private Long allTeamCompleteCount;
    private Long allTeamUserCount;
}
