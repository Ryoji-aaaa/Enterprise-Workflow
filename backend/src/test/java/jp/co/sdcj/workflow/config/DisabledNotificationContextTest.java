package jp.co.sdcj.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;

import jp.co.sdcj.workflow.domain.NotificationType;
import jp.co.sdcj.workflow.repository.NotificationOutboxRepository;
import jp.co.sdcj.workflow.service.notification.MailpitNotificationDispatcher;
import jp.co.sdcj.workflow.service.notification.NoopNotificationPublisher;
import jp.co.sdcj.workflow.service.notification.NotificationPublisher;
import jp.co.sdcj.workflow.service.notification.NotificationRequest;
import jp.co.sdcj.workflow.service.notification.OutboxNotificationPublisher;

@SpringBootTest(properties = "workflow.notification.delivery-mode=disabled")
@ActiveProfiles("test")
class DisabledNotificationContextTest {
    @Autowired ApplicationContext context;
    @Autowired NotificationOutboxRepository outboxRepository;

    @Test
    void disabledではSMTPとDispatcherとOutboxPublisherを登録しない() {
        assertThat(context.getBeansOfType(JavaMailSender.class)).isEmpty();
        assertThat(context.getBeansOfType(MailpitNotificationDispatcher.class)).isEmpty();
        assertThat(context.getBeansOfType(OutboxNotificationPublisher.class)).isEmpty();
        assertThat(context.getBeansOfType(NotificationPublisher.class))
                .hasSize(1)
                .allSatisfy((name, publisher) ->
                        assertThat(publisher).isInstanceOf(NoopNotificationPublisher.class));

        NotificationPublisher publisher = context.getBean(NotificationPublisher.class);
        publisher.publish(new NotificationRequest(
                NotificationType.ACCESS_REQUEST,
                "ACCESS_REQUEST",
                UUID.randomUUID(),
                null,
                null,
                null,
                null,
                "管理者",
                "admin@sdcj.co.jp",
                "件名",
                "本文",
                "disabled-test"));
        assertThat(outboxRepository.count()).isZero();
    }
}
