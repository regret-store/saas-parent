local key = KEYS[1]
local cost = tonumber(ARGV[1])

-- 获取值并安全地转成数字
local current_str = redis.call('GET', key)
if not current_str then
    return -1
end

local current = tonumber(current_str)
-- 如果转换失败（比如存了非数字字符），直接拒绝
if not current then
    return -1
end

if current >= cost then
    redis.call('DECRBY', key, cost)
    return 1
else
    return -1
end