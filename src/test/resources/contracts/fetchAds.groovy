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
            geo: 'test-geo',
            deviceId: 'device-123',
            context: 'test-context'
        ])
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body([[
            adId: 'AD-12345-campaign-1',
            campaignId: 'campaign-1',
            impressionUrl: 'http://impression',
            clickUrl: 'http://click',
            adm: 'http://cdn/sponsored.jpg',
            creativeFormat: 'BANNER'
        ]])
    }
}
