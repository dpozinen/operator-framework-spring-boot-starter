package io.javaoperatorsdk.operator.springboot.starter;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.Operator;
import io.javaoperatorsdk.operator.ReconcilerUtils;
import io.javaoperatorsdk.operator.api.config.Cloner;
import io.javaoperatorsdk.operator.api.config.ConfigurationServiceOverrider;
import io.javaoperatorsdk.operator.api.config.ConfigurationServiceProvider;
import io.javaoperatorsdk.operator.api.config.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.processing.retry.GenericRetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.atIndex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;

@SpringBootTest(properties = {
    "javaoperatorsdk.client.masterUrl=http://master.url",
    "javaoperatorsdk.client.username=user",
    "javaoperatorsdk.client.password=password",
    "javaoperatorsdk.client.oauthToken=token"
})
public class AutoConfigurationTest {

  static final int CUSTOM_RECONCILE_THREADS = 42;

  @Autowired
  private OperatorConfigurationProperties config;

  @SpyBean
  private Operator operator;

  @Autowired
  private KubernetesClient kubernetesClient;

  @Autowired
  private List<Reconciler<?>> reconcilers;

  @Autowired
  private Consumer<ConfigurationServiceOverrider> compositeConfigurationServiceOverrider;

  @MockBean
  private Cloner cloner;

  @BeforeAll
  static void beforeAll() {
    ConfigurationServiceProvider.reset();
  }

  @Test
  public void loadsKubernetesClientPropertiesProperly() {
    final var operatorProperties = config.getClient();
    assertEquals("user", operatorProperties.getUsername().get());
    assertEquals("password", operatorProperties.getPassword().get());
    assertEquals("token", operatorProperties.getOauthToken().get());
    assertEquals("http://master.url", operatorProperties.getMasterUrl().get());
  }

  @Test
  public void loadsRetryPropertiesProperly() {
    final var retryProperties =
        config.getReconcilers().get(ReconcilerUtils.getNameFor(TestReconciler.class)).getRetry();
    assertEquals(3, retryProperties.getMaxAttempts());
    assertEquals(1000, retryProperties.getInitialInterval());
    assertEquals(1.5, retryProperties.getIntervalMultiplier());
    assertEquals(50000, retryProperties.getMaxInterval());
  }

  @Test
  public void beansCreated() {
    assertNotNull(kubernetesClient);
    assertNotNull(compositeConfigurationServiceOverrider);
  }

  @Test
  public void configServiceOverridesAppliedInCorrectOrder() {
    var configService = ConfigurationServiceProvider.instance();

    assertThat(config.getConcurrentReconciliationThreads())
        .isEqualTo(60).isNotEqualTo(CUSTOM_RECONCILE_THREADS);
    assertThat(configService.concurrentReconciliationThreads())
        .isEqualTo(CUSTOM_RECONCILE_THREADS);
    assertEquals(configService.getResourceCloner(), cloner);
    assertThat(configService.cacheSyncTimeout()).isEqualTo(Duration.ofDays(17));
    assertThat(configService.closeClientOnStop()).isFalse();
    assertThat(configService.stopOnInformerErrorDuringStartup()).isFalse();
    assertThat(configService.checkCRDAndValidateLocalModel()).isFalse();
    assertThat(configService.minConcurrentReconciliationThreads()).isEqualTo(22);
    assertThat(configService.concurrentWorkflowExecutorThreads()).isEqualTo(32);
    assertThat(configService.minConcurrentWorkflowExecutorThreads()).isEqualTo(12);
  }

  @Test
  public void reconcilersAreDiscovered() {
    assertEquals(1, reconcilers.size());
    assertTrue(reconcilers.get(0) instanceof TestReconciler);
  }

  @Test
  void appliesConfigPropertiesToControllers() {
    var controllers = operator.getRegisteredControllers();

    assertThat(controllers.stream().toList())
        .satisfies(controller -> {
          assertThat((ControllerConfiguration<?>) controller.getConfiguration())
              .satisfies(config -> {
                assertThat(config.getNamespaces()).containsExactlyInAnyOrder("ns1", "ns2");
                assertThat(config.isGenerationAware()).isTrue();
                assertThat(config.getName()).isEqualTo("not-a-test-reconciler");
                assertThat(config.getFinalizerName()).isEqualTo("barton.fink/1991");
                assertThat(config.getLabelSelector()).isEqualTo("version in (v1)");
                assertThat(config.maxReconciliationInterval()).hasValue(Duration.ofMinutes(3));
              });
          assertThat(controller.getConfiguration().getRetry())
              .isInstanceOfSatisfying(GenericRetry.class, retry -> {
                assertThat(retry.getMaxAttempts()).isEqualTo(3);
                assertThat(retry.getInitialInterval()).isEqualTo(1000);
                assertThat(retry.getIntervalMultiplier()).isEqualTo(1.5);
                assertThat(retry.getMaxInterval()).isEqualTo(50000);
              });
        }, atIndex(0));
  }

  @TestConfiguration
  static class TestConfig {
    @Bean
    public BeanPostProcessor operatorPostProcessor() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName)
            throws BeansException {
          if (bean instanceof Operator operator) {
            doNothing().when(operator).start();
          }
          return bean;
        }
      };
    }

    @Bean
    public Consumer<ConfigurationServiceOverrider> additionalConfigServiceOverrider() {
      return overrider -> overrider.withConcurrentReconciliationThreads(CUSTOM_RECONCILE_THREADS);
    }

  }

}
