package name.mrkandreev.app;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Instant;
import java.util.List;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@SpringBootApplication
public class AppApplication {

  public static void main(String[] args) {
    SpringApplication.run(AppApplication.class, args);
  }

}

@RestController
class AppController {
  @GetMapping
  @LimitRate
  public String getCurrentTime() {
    return Instant.now().toString();
  }
}

@ControllerAdvice
class DefaultControllerAdvice {
  @ExceptionHandler(RateLimitException.class)
  @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
  @ResponseBody
  public String rateLimitException() {
    return "Too-many-requests";
  }
}

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface LimitRate {
}

@Component
@Aspect
@RequiredArgsConstructor
class LimitRateAspect {
  private final RateLimitService rateLimitService;

  @Before(value = "execution(* *(..)) && @annotation(name.mrkandreev.app.LimitRate)")
  public void invoke() {
    var attr = RequestContextHolder.getRequestAttributes();
    String remoteAddr = ((ServletRequestAttributes) attr).getRequest().getHeader("X-Forwarded-For");
    if (remoteAddr != null) {
      rateLimitService.handle(remoteAddr);
    }
  }
}

@ConfigurationProperties("app")
@Component
@Data
class RateLimitOption {
  private long windowSecondsSize;

  private long maxRate;
}

class RateLimitException extends RuntimeException {
}

@Service
@RequiredArgsConstructor
@Log4j2
class RateLimitService {
  private final RedisTemplate<String, String> redisTemplate;
  private final RateLimitOption rateLimitOption;

  public void handle(String key) {
    var response = redisTemplate.execute(new SessionCallback<List<Object>>() {
      @Override
      public List<Object> execute(RedisOperations ops) throws DataAccessException {
        ops.multi(); // Mark the start of a transaction block.
        var zSetOps = ops.opsForZSet();

        var windowEnd =
            Instant.now().minusSeconds(rateLimitOption.getWindowSecondsSize()).toEpochMilli();
        zSetOps.removeRangeByScore(key, 0, windowEnd);
        zSetOps.add(key, Instant.now().toString(), Instant.now().toEpochMilli());
        zSetOps.size(key);
        return ops.exec();
      }
    });
    if (rateLimitOption.getMaxRate() < (Long) response.get(response.size() - 1)) {
      throw new RateLimitException();
    }
  }
}
