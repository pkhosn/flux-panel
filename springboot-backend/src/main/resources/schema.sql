-- SQLite Auto-generated schema
-- This will be executed automatically on startup if tables don't exist

CREATE TABLE IF NOT EXISTS forward (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  user_name VARCHAR(100) NOT NULL,
  name VARCHAR(100) NOT NULL,
  tunnel_id INTEGER NOT NULL,
  remote_addr TEXT NOT NULL,
  strategy VARCHAR(100) NOT NULL DEFAULT 'fifo',
  protocol VARCHAR(10) NOT NULL DEFAULT 'tcp_udp',
  origin_tls INTEGER NOT NULL DEFAULT 0,
  origin_server_name TEXT NOT NULL DEFAULT '',
  origin_root_ca TEXT NOT NULL DEFAULT '',
  origin_alpn TEXT NOT NULL DEFAULT '',
  proxy_accept INTEGER NOT NULL DEFAULT 0,
  proxy_send INTEGER NOT NULL DEFAULT 0,
  proxy_trusted_cidrs TEXT NOT NULL DEFAULT '',
  group_id INTEGER,
  in_flow INTEGER NOT NULL DEFAULT 0,
  out_flow INTEGER NOT NULL DEFAULT 0,
  traffic_baseline INTEGER NOT NULL DEFAULT 0,
  created_time INTEGER NOT NULL,
  updated_time INTEGER NOT NULL,
  status INTEGER NOT NULL,
  inx INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS forward_group (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  name VARCHAR(64) NOT NULL,
  UNIQUE(user_id, name)
);

CREATE TABLE IF NOT EXISTS forward_flow_hour (
  forward_id INTEGER NOT NULL,
  user_id INTEGER NOT NULL,
  hour_start INTEGER NOT NULL,
  bytes INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (forward_id, hour_start)
);

CREATE INDEX IF NOT EXISTS idx_forward_flow_hour_user_time
ON forward_flow_hour(user_id, hour_start);

CREATE TABLE IF NOT EXISTS forward_port (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  forward_id INTEGER NOT NULL,
  node_id INTEGER NOT NULL,
  port INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS node (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name VARCHAR(100) NOT NULL,
  secret VARCHAR(100) NOT NULL,
  server_ip VARCHAR(100) NOT NULL DEFAULT '',
  detected_ipv4 VARCHAR(100),
  detected_ipv6 VARCHAR(100),
  port TEXT NOT NULL,
  interface_name VARCHAR(200),
  version VARCHAR(100),
  http INTEGER NOT NULL DEFAULT 0,
  tls INTEGER NOT NULL DEFAULT 0,
  socks INTEGER NOT NULL DEFAULT 0,
  created_time INTEGER NOT NULL,
  updated_time INTEGER,
  status INTEGER NOT NULL,
  owner_user_id INTEGER,
  created_by_role INTEGER NOT NULL DEFAULT 0,
  tcp_listen_addr VARCHAR(100) NOT NULL DEFAULT '[::]',
  udp_listen_addr VARCHAR(100) NOT NULL DEFAULT '[::]'
);

CREATE TABLE IF NOT EXISTS speed_limit (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name VARCHAR(100) NOT NULL,
  speed INTEGER NOT NULL,
  tunnel_id INTEGER NOT NULL,
  tunnel_name VARCHAR(100) NOT NULL,
  created_time INTEGER NOT NULL,
  updated_time INTEGER,
  status INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS statistics_flow (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  flow INTEGER NOT NULL,
  total_flow INTEGER NOT NULL,
  time VARCHAR(100) NOT NULL,
  created_time INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS tunnel (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name VARCHAR(100) NOT NULL,
  traffic_ratio REAL NOT NULL DEFAULT 1.0,
  type INTEGER NOT NULL,
  protocol VARCHAR(10) NOT NULL DEFAULT 'tls',
  flow INTEGER NOT NULL,
  created_time INTEGER NOT NULL,
  updated_time INTEGER NOT NULL,
  status INTEGER NOT NULL,
  owner_user_id INTEGER,
  created_by_role INTEGER NOT NULL DEFAULT 0,
  in_ip TEXT
);

CREATE TABLE IF NOT EXISTS chain_tunnel (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    tunnel_id INTEGER NOT NULL ,
    chain_type VARCHAR(10) NOT NULL,
    node_id INTEGER NOT NULL ,
    port INTEGER,
    strategy VARCHAR(10),
    inx  INTEGER,
    protocol  VARCHAR(10)
);


CREATE TABLE IF NOT EXISTS user (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user VARCHAR(100) NOT NULL,
  pwd VARCHAR(100) NOT NULL,
  role_id INTEGER NOT NULL,
  exp_time INTEGER NOT NULL,
  flow INTEGER NOT NULL,
  in_flow INTEGER NOT NULL DEFAULT 0,
  out_flow INTEGER NOT NULL DEFAULT 0,
  flow_reset_time INTEGER NOT NULL,
  num INTEGER NOT NULL,
  created_time INTEGER NOT NULL,
  updated_time INTEGER,
  status INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS user_tunnel (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  tunnel_id INTEGER NOT NULL,
  speed_id INTEGER,
  num INTEGER NOT NULL,
  flow INTEGER NOT NULL,
  in_flow INTEGER NOT NULL DEFAULT 0,
  out_flow INTEGER NOT NULL DEFAULT 0,
  flow_reset_time INTEGER NOT NULL,
  exp_time INTEGER NOT NULL,
  status INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS user_node_permission (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  node_id INTEGER NOT NULL,
  allow_in INTEGER NOT NULL DEFAULT 0,
  allow_out INTEGER NOT NULL DEFAULT 0,
  created_time INTEGER NOT NULL,
  updated_time INTEGER,
  status INTEGER NOT NULL DEFAULT 1,
  UNIQUE(user_id, node_id)
);

CREATE TABLE IF NOT EXISTS vite_config (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name VARCHAR(200) NOT NULL UNIQUE,
  value VARCHAR(200) NOT NULL,
  time INTEGER NOT NULL
);
