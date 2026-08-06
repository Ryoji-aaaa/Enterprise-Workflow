package jp.co.sdcj.workflow.service.notification;

public interface NotificationPublisher {
    void publish(NotificationRequest request);
}
