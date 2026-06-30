package dev.sophi.web

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SophiWebApplication

fun main(args: Array<String>) {
    runApplication<SophiWebApplication>(*args)
}
