
import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description("should fetch ads")
    request {
        method 'POST'
        url '/api/v1/ads/serve'
        headers {
            contentType applicationJson()
        }
        body([
            userId: $(consumer(regex('.*')), producer('user123')),
            location: 'test'
        ])
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body([
            adId: 'ad123',
            content: 'Test Ad'
        ])
    }
}
