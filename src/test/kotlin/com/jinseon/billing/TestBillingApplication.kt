package com.jinseon.billing

import org.springframework.boot.fromApplication
import org.springframework.boot.with

fun main(args: Array<String>) {
    fromApplication<BillingApplication>().with(TestcontainersConfiguration::class).run(*args)
}
