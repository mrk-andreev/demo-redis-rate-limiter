# Rate limit with sliding window

## Solution

Use ZSet for events. On each request do in transaction:

1. removeRangeByScore(key, 0, currentTimestamp - windowSize)
2. add(key, currentTimestamp, currentTimestamp)
3. size

If size > threshold then throw exception

## Implementation

1. Spring MVC
2. RedisTemplate for transactions
3. Spring Aop for handle audited requests
