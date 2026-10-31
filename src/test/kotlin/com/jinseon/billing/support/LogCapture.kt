package com.jinseon.billing.support

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

// 테스트 동안 특정 클래스가 남기는 로그를 모은다. use { } 블록이 끝나면 자동으로 떼어낸다.
class LogCapture(
    loggerClass: Class<*>,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(loggerClass) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    init {
        appender.start()
        logger.addAppender(appender)
    }

    fun messages(level: Level): List<String> = appender.list.filter { it.level == level }.map { it.formattedMessage }

    override fun close() {
        logger.detachAppender(appender)
        appender.stop()
    }
}
