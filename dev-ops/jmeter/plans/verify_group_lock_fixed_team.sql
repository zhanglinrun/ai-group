-- Read-only snapshot template for verify_group_lock_fixed_team.py. Parameters are
-- substituted only after strict identifier validation; do not execute manually.
SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION READ ONLY, WITH CONSISTENT SNAPSHOT;
SELECT JSON_OBJECT('kind','identity','database',DATABASE(),'serverUuid',@@server_uuid,'serverTime',DATE_FORMAT(NOW(),'%Y-%m-%d %H:%i:%s'));
SELECT JSON_OBJECT('kind','team','teamId',team_id,'activityId',activity_id,
    'source',source,'channel',channel,'status',status,
    'originalPrice',original_price,'deductionPrice',deduction_price,'payPrice',pay_price,
    'validStartTime',DATE_FORMAT(valid_start_time,'%Y-%m-%d %H:%i:%s'),
    'validEndTime',DATE_FORMAT(valid_end_time,'%Y-%m-%d %H:%i:%s'),
    'notifyType',notify_type,'notifyUrl',notify_url,
    'unexpired',valid_end_time > NOW(),'targetCount',target_count,'lockCount',lock_count,'completeCount',complete_count)
FROM group_buy_order WHERE team_id = @team_id;
SELECT JSON_OBJECT('kind','mapping','source',source,'channel',channel,'goodsId',goods_id,'activityId',activity_id)
FROM sc_sku_activity WHERE source = 's01' AND channel = 'c01' AND goods_id = @goods_id;
-- All target team rows plus every row using this exact run prefix, even on another team.
SELECT JSON_OBJECT('kind','detail','id',id,'teamId',team_id,'activityId',activity_id,
    'goodsId',goods_id,'source',source,'channel',channel,'status',status,
    'outTradeNo',out_trade_no,'orderId',order_id,'bizId',biz_id,
    'rowDigest',SHA2(CAST(JSON_OBJECT('userId',user_id,'startTime',start_time,'endTime',end_time,
        'originalPrice',original_price,'deductionPrice',deduction_price,'payPrice',pay_price,
        'outTradeTime',out_trade_time,'createTime',create_time,'updateTime',update_time) AS CHAR),256))
FROM group_buy_order_list
WHERE team_id = @team_id OR out_trade_no LIKE CONCAT('jmeter-spike-', @run_id, '-%')
ORDER BY id;
SELECT JSON_OBJECT('kind','outbox','id',id,'uuid',uuid,'activityId',activity_id,
    'notifyStatus',notify_status,'notifyCount',notify_count,
    'rowDigest',SHA2(CAST(JSON_OBJECT('id',id,'teamId',team_id,'activityId',activity_id,
        'uuid',uuid,'notifyCategory',notify_category,'notifyType',notify_type,
        'notifyMq',notify_mq,'notifyUrl',notify_url,'notifyCount',notify_count,
        'notifyStatus',notify_status,'parameterJson',parameter_json,
        'createTime',create_time,'updateTime',update_time) AS CHAR),256))
FROM notify_task WHERE team_id = @team_id ORDER BY id;
COMMIT;
