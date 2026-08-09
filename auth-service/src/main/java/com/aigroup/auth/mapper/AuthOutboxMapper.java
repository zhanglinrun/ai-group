package com.aigroup.auth.mapper;

import com.aigroup.auth.entity.AuthOutboxEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AuthOutboxMapper extends BaseMapper<AuthOutboxEvent> {

    @Select("""
            SELECT * FROM auth_outbox_event
            WHERE status = 'PENDING'
              AND (next_attempt_at IS NULL OR next_attempt_at <= NOW())
            ORDER BY id
            LIMIT 50
            """)
    List<AuthOutboxEvent> selectPending();

    @Update("""
            UPDATE auth_outbox_event
               SET status = 'PROCESSING', attempts = attempts + 1
             WHERE id = #{id} AND status = 'PENDING'
            """)
    int claim(Long id);

    @Update("""
            UPDATE auth_outbox_event
               SET status = 'SENT', sent_at = NOW(), last_error = NULL
             WHERE id = #{id} AND status = 'PROCESSING'
            """)
    int markSent(Long id);

    @Update("""
            UPDATE auth_outbox_event
               SET status = 'PENDING', next_attempt_at = DATE_ADD(NOW(), INTERVAL LEAST(300, POW(2, LEAST(attempts, 8))) SECOND),
                   last_error = #{error}
             WHERE id = #{id} AND status = 'PROCESSING'
            """)
    int markFailed(Long id, String error);
}
