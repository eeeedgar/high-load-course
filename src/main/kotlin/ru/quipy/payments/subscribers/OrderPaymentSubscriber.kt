package ru.quipy.payments.subscribers

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.core.EventSourcingService
import ru.quipy.orders.api.OrderAggregate
import ru.quipy.orders.api.OrderPaymentStartedEvent
import ru.quipy.payments.api.PaymentAggregate
import ru.quipy.payments.config.ExternalServicesConfig
import ru.quipy.payments.logic.*
import ru.quipy.streams.AggregateSubscriptionsManager
import ru.quipy.streams.annotation.RetryConf
import ru.quipy.streams.annotation.RetryFailedStrategy
import java.time.Duration
import java.util.*
import java.util.concurrent.Executors
import javax.annotation.PostConstruct

@Service
class OrderPaymentSubscriber {

    val logger: Logger = LoggerFactory.getLogger(OrderPaymentSubscriber::class.java)

    @Autowired
    lateinit var subscriptionsManager: AggregateSubscriptionsManager

    @Autowired
    private lateinit var paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>

    @Autowired
    @Qualifier(ExternalServicesConfig.ACCOUNT_1)
    private lateinit var paymentService1: PaymentService

    @Autowired
    @Qualifier(ExternalServicesConfig.ACCOUNT_2)
    private lateinit var paymentService2: PaymentService

    @Autowired
    @Qualifier(ExternalServicesConfig.ACCOUNT_3)
    private lateinit var paymentService3: PaymentService

    @Autowired
    @Qualifier(ExternalServicesConfig.ACCOUNT_4)
    private lateinit var paymentService4: PaymentService

    private val paymentExecutor = Executors.newFixedThreadPool(16, NamedThreadFactory("payment-executor"))

    @PostConstruct
    fun init() {
        val paymentServices = listOf(paymentService4, paymentService3, paymentService2, paymentService1)
        subscriptionsManager.createSubscriber(OrderAggregate::class, "payments:order-subscriber", retryConf = RetryConf(1, RetryFailedStrategy.SKIP_EVENT)) {
            `when`(OrderPaymentStartedEvent::class) { event ->
                paymentExecutor.submit {
                    val createdEvent = paymentESService.create {
                        it.create(
                                event.paymentId,
                                event.orderId,
                                event.amount
                        )
                    }
                    logger.info("Payment ${createdEvent.paymentId} for order ${event.orderId} created.")

                    var chosenPaymentService: PaymentService? = null
                    for (paymentService in paymentServices) {
                        if (paymentService.canProcess(createdEvent.paymentId, event.amount, event.createdAt)) {
                            chosenPaymentService = paymentService
                            break
                        }
                    }
                    if (chosenPaymentService != null) {
                        chosenPaymentService.submitPaymentRequest(createdEvent.paymentId, event.amount, event.createdAt)
                    } else {
                        logger.error("Could not choose account for payment: ${createdEvent.paymentId}")

                        paymentESService.update(createdEvent.paymentId) {
                            it.logSubmission(success = false, UUID.randomUUID(), now(), Duration.ofMillis(now() - event.createdAt))
                        }
                        paymentESService.update(createdEvent.paymentId) {
                            it.logProcessing(false, now(), null, reason = "Could not choose account for payment")
                        }
                    }
                }
            }
        }
    }
}
