-- 参数1：桶的 Key
local key = KEYS[1]
-- 参数2：桶的容量（最大突发请求数）
local capacity = tonumber(ARGV[1])
-- 参数3：令牌生成速率（每秒生成多少个）
local rate = tonumber(ARGV[2])
-- 参数4：当前时间戳（秒）
local now = tonumber(ARGV[3])
-- 参数5：每次请求消耗的令牌数
local requested = tonumber(ARGV[4])

-- 获取桶的当前状态：[最后刷新时间, 当前令牌数]
local bucket = redis.call('HMGET', key, 'last_refill_time', 'tokens')
local last_refill_time = tonumber(bucket[1])
local tokens = tonumber(bucket[2])

-- 如果是第一次请求，初始化桶
if not last_refill_time then
    last_refill_time = now
    tokens = capacity
end

-- 计算从上一次刷新到现在，应该补充多少令牌
local delta = math.max(0, now - last_refill_time)
local filled_tokens = math.min(capacity, tokens + (delta * rate))

-- 判断是否足够扣减
local allowed = filled_tokens >= requested
local new_tokens = filled_tokens
if allowed then
    new_tokens = filled_tokens - requested
end

-- 更新 Redis 中的桶状态
redis.call('HMSET', key, 'last_refill_time', now, 'tokens', new_tokens)
-- 设置过期时间，防止冷数据占用内存
redis.call('EXPIRE', key, 60)

if allowed then
    return 1
else
    return 0
end