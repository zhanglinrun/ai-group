-- Native Spring AI Alibaba DEEP graph checkpoint projection.
-- This table intentionally does not reuse LANGRAPH4J_CHECKPOINT: it stores
-- only the P40 recoverable projection, never prompts, hidden reasoning or raw model output.

use agent_db;

create table if not exists `deep_research_graph_checkpoint` (
  `graph_id` varchar(128) not null,
  `thread_id` varchar(128) not null,
  `status` varchar(64) not null,
  `terminal` tinyint not null default 0,
  `checkpoint_state` json not null,
  `observed_at` datetime(6) not null,
  `version` bigint not null default 0,
  primary key (`graph_id`, `thread_id`),
  key `idx_deep_research_checkpoint_observed` (`observed_at`)
) engine=InnoDB default charset=utf8mb4;
