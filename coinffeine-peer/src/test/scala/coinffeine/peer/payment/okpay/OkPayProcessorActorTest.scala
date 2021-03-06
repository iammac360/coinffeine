package coinffeine.peer.payment.okpay

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future
import scala.concurrent.duration._

import akka.actor.{ActorRef, Props}
import org.mockito.BDDMockito.given
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.concurrent.{PatienceConfiguration, Eventually}
import org.scalatest.mock.MockitoSugar

import coinffeine.common.akka.ServiceActor
import coinffeine.common.akka.test.AkkaSpec
import coinffeine.model.currency.Currency.UsDollar
import coinffeine.model.currency.Implicits._
import coinffeine.model.currency.{Balance, FiatAmount, FiatCurrency}
import coinffeine.model.event.EventChannelProbe
import coinffeine.model.payment.{OkPayPaymentProcessor, Payment, PaymentProcessor}
import coinffeine.peer.payment.{MutablePaymentProcessorProperties, PaymentProcessorActor}

class OkPayProcessorActorTest extends AkkaSpec("OkPayTest") with MockitoSugar with Eventually {

  "OKPayProcessor" must "identify itself" in new WithOkPayProcessor {
    givenPaymentProcessorIsInitialized()
    processor ! PaymentProcessorActor.RetrieveAccountId
    expectMsg(PaymentProcessorActor.RetrievedAccountId(senderAccount))
  }

  it must "be able to get the current balance" in new WithOkPayProcessor {
    givenPaymentProcessorIsInitialized(balances = Seq(amount))
    processor ! PaymentProcessorActor.RetrieveBalance(UsDollar)
    expectMsg(PaymentProcessorActor.BalanceRetrieved(amount, UsDollar.Zero))
  }

  it must "update properties when asked to get the current balance" in new WithOkPayProcessor {
    givenPaymentProcessorIsInitialized(balances = Seq(amount))
    val nextAmount = amount * 2
    given(client.currentBalances()).willReturn(Future.successful(Seq(nextAmount)))
    processor ! PaymentProcessorActor.RetrieveBalance(UsDollar)
    expectMsgClass(classOf[PaymentProcessorActor.BalanceRetrieved[FiatCurrency]])

    expectBalanceUpdate(nextAmount)
  }

  it must "report failure to get the current balance" in new WithOkPayProcessor {
    given(client.currentBalances()).willReturn(Future.failed(cause))
    given(client.currentBalance(UsDollar)).willReturn(Future.failed(cause))
    processor = system.actorOf(Props(
      new OkPayProcessorActor(clientParams, properties)))
    processor ! ServiceActor.Start({})
    expectMsg(ServiceActor.Started)
    processor ! PaymentProcessorActor.RetrieveBalance(UsDollar)
    expectMsg(PaymentProcessorActor.BalanceRetrievalFailed(UsDollar, cause))
  }

  it must "be able to send a payment that gets reserved funds reduced" in new WithOkPayProcessor {
    given(client.sendPayment(receiverAccount, amount, "comment"))
      .willReturn(Future.successful(payment))
    givenPaymentProcessorIsInitialized(balances = Seq(OkPayPaymentProcessor.amountPlusFee(amount)))
    processor ! PaymentProcessorActor.BlockFunds(OkPayPaymentProcessor.amountPlusFee(amount))
    val funds = expectMsgClass(classOf[PaymentProcessor.BlockedFundsId])
    expectMsg(PaymentProcessorActor.AvailableFunds(funds))
    processor ! PaymentProcessorActor.Pay(funds, receiverAccount, amount, "comment")
    val response = expectMsgType[PaymentProcessorActor.Paid[_ <: FiatCurrency]].payment
    response.id should be (payment.id)
    response.senderId should be (senderAccount)
    response.receiverId should be (receiverAccount)
    response.amount should be (amount)
    response.description should be ("comment")

    withClue("the fee has been taken into account") {
      processor ! PaymentProcessorActor.Pay(funds, receiverAccount, 0.01.USD, "comment")
      expectMsgPF() {
        case PaymentProcessorActor.PaymentFailed(_, ex) =>
          ex.toString should include ("fail to use funds")
      }
    }
  }

  it must "require enough funds to send a payment" in new WithOkPayProcessor {
    givenPaymentProcessorIsInitialized(balances = Seq(amount))
    processor ! PaymentProcessorActor.BlockFunds(amount / 2)
    val funds = expectMsgClass(classOf[PaymentProcessor.BlockedFundsId])
    expectMsg(PaymentProcessorActor.AvailableFunds(funds))
    processor ! PaymentProcessorActor.Pay(funds, receiverAccount, amount, "comment")
    expectMsgClass(classOf[PaymentProcessorActor.PaymentFailed[_]])
  }

  it must "report failure to send a payment" in new WithOkPayProcessor {
    given(client.sendPayment(receiverAccount, amount, "comment")).willReturn(Future.failed(cause))
    givenPaymentProcessorIsInitialized(balances = Seq(OkPayPaymentProcessor.amountPlusFee(amount)))
    processor ! PaymentProcessorActor.BlockFunds(OkPayPaymentProcessor.amountPlusFee(amount))
    val funds = expectMsgClass(classOf[PaymentProcessor.BlockedFundsId])
    expectMsg(PaymentProcessorActor.AvailableFunds(funds))
    val payRequest = PaymentProcessorActor.Pay(funds, receiverAccount, amount, "comment")
    processor ! payRequest
    expectMsg(PaymentProcessorActor.PaymentFailed(payRequest, cause))
  }

  it must "be able to retrieve an existing payment" in new WithOkPayProcessor {
    given(client.findPayment(payment.id)).willReturn(Future.successful(Some(payment)))
    givenPaymentProcessorIsInitialized()
    processor ! PaymentProcessorActor.FindPayment(payment.id)
    expectMsgType[PaymentProcessorActor.PaymentFound]
  }

  it must "be able to check a payment does not exist"  in new WithOkPayProcessor {
    given(client.findPayment(payment.id)).willReturn(Future.successful(None))
    givenPaymentProcessorIsInitialized()
    processor ! PaymentProcessorActor.FindPayment(payment.id)
    expectMsg(PaymentProcessorActor.PaymentNotFound(payment.id))
  }

  it must "report failure to retrieve a payment" in new WithOkPayProcessor {
    given(client.findPayment(payment.id)).willReturn(Future.failed(cause))
    givenPaymentProcessorIsInitialized()
    processor ! PaymentProcessorActor.FindPayment(payment.id)
    expectMsg(PaymentProcessorActor.FindPaymentFailed(payment.id, cause))
  }

  it must "poll for EUR balance periodically" in new WithOkPayProcessor {
    override def pollingInterval = 1.second
    givenPaymentProcessorIsInitialized(balances = Seq(100.EUR))
    given(client.currentBalances()).willReturn(
      Future.successful(Seq(120.EUR)),
      Future.successful(Seq(140.EUR)),
      Future.failed(new Exception("doesn't work"))
    )
    expectBalanceUpdate(120.EUR, timeout = 2.seconds)
    expectBalanceUpdate(140.EUR, timeout = 2.seconds)
    expectBalanceUpdate(140.EUR, hasExpired = true, timeout = 2.seconds)
  }

  private trait WithOkPayProcessor {
    def pollingInterval = 3.seconds
    val senderAccount = "OK12345"
    val receiverAccount = "OK54321"
    val amount = 100.USD
    val payment = Payment(
      id = "250092",
      senderId = senderAccount,
      receiverId = receiverAccount,
      amount = amount,
      date = OkPayWebServiceClient.DateFormat.parseDateTime("2014-01-20 14:00:00"),
      description = "comment",
      completed = true
    )
    val cause = new Exception("Sample error")
    val client = mock[OkPayClient]
    val eventChannelProbe = EventChannelProbe()
    var processor: ActorRef = _
    val properties = new MutablePaymentProcessorProperties
    val clientParams = OkPayProcessorActor.ClientParams(senderAccount, client, pollingInterval)

    def givenPaymentProcessorIsInitialized(balances: Seq[FiatAmount] = Seq.empty): Unit = {
      given(client.currentBalances()).willReturn(Future.successful(balances))

      processor = system.actorOf(Props(
        new OkPayProcessorActor(clientParams, properties)))
      processor ! ServiceActor.Start({})
      expectMsg(ServiceActor.Started)
    }

    def expectBalanceUpdate(balance: FiatAmount,
                            hasExpired: Boolean = false,
                            timeout: FiniteDuration = 200.millis): Unit = {
      eventually(PatienceConfiguration.Timeout(timeout)) {
        properties.balance(balance.currency) should be (Balance(balance, hasExpired))
      }
    }
  }

  class MutableBalances(initialBalances: FiatAmount*) extends Answer[Future[Seq[FiatAmount]]] {
    private val balances = new AtomicReference[Seq[FiatAmount]](initialBalances)

    override def answer(invocation: InvocationOnMock) = Future.successful(balances.get())

    def set(newBalances: FiatAmount*): Unit = {
      balances.set(newBalances)
    }
  }
}
