package jp.co.sdcj.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;

import jp.co.sdcj.workflow.service.notification.MailpitNotificationDispatcher;
import jp.co.sdcj.workflow.service.notification.NoopNotificationPublisher;
import jp.co.sdcj.workflow.service.notification.NotificationPublisher;
import jp.co.sdcj.workflow.service.notification.OutboxNotificationPublisher;

@SpringBootTest(properties = "workflow.notification.delivery-mode=disabled")
@ActiveProfiles("test")
class DisabledNotificationContextTest {
    @Autowired ApplicationContext context;

    @Test
    void disabledではSMTPとDispatcherとOutboxPublisherを登録しない() {
        assertThat(context.getBeansOfType(JavaMailSender.class)).isEmpty();
        assertThat(context.getBeansOfType(MailpitNotificationDispatcher.class)).isEmpty();
        assertThat(context.getBeansOfType(OutboxNotificationPublisher.class)).isEmpty();
        assertThat(context.getBeansOfType(NotificationPublisher.class))
                .hasSize(1)
                .allSatisfy((name, publisher) ->
                        assertThat(publisher).isInstanceOf(NoopNotificationPublisher.class));
    }
}
