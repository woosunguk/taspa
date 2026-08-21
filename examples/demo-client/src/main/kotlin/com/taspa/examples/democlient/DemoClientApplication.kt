package com.taspa.examples.democlient

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DemoClientApplication

fun main(args: Array<String>) {
    runApplication<DemoClientApplication>(*args)
}
