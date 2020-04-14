package com.guymichael.apromise.promise

object Logger {
    var mLogger: LoggerIntf? = null

    fun init(logger: LoggerIntf) {
        mLogger = logger
    }

    fun i(cls: Class<*>, msg: String) {
        mLogger?.takeIf { it.shouldLogI() }?.i(cls.simpleName, msg)
    }

    fun d(cls: Class<*>, msg: String) {
        mLogger?.takeIf { it.shouldLogD() }?.d(cls.simpleName, msg)
    }

    fun w(cls: Class<*>, msg: String) {
        mLogger?.takeIf { it.shouldLogW() }?.w(cls.simpleName, msg)
    }

    fun e(cls: Class<*>, msg: String) {
        mLogger?.takeIf { it.shouldLogE() }?.e(cls.simpleName, msg)
    }

    fun i(tag: String, msg: String) {
        mLogger?.takeIf { it.shouldLogI() }?.i(tag, msg)
    }

    fun d(tag: String, msg: String) {
        mLogger?.takeIf { it.shouldLogD() }?.d(tag, msg)
    }

    fun w(tag: String, msg: String) {
        mLogger?.takeIf { it.shouldLogW() }?.w(tag, msg)
    }

    fun e(tag: String, msg: String) {
        mLogger?.takeIf { it.shouldLogE() }?.e(tag, msg)
    }






    fun iLazy(cls: Class<*>, messageSupplier: () -> String) {
        mLogger?.takeIf { it.shouldLogI() }?.i(cls.simpleName, messageSupplier())
    }

    fun dLazy(cls: Class<*>, messageSupplier: () -> String) {
        mLogger?.takeIf { it.shouldLogD() }?.d(cls.simpleName, messageSupplier())
    }

    fun wLazy(cls: Class<*>, messageSupplier: () -> String) {
        mLogger?.takeIf { it.shouldLogW() }?.i(cls.simpleName, messageSupplier())
    }

    fun eLazy(cls: Class<*>, messageSupplier: () -> String) {
        mLogger?.takeIf { it.shouldLogE() }?.e(cls.simpleName, messageSupplier())
    }
}




interface LoggerIntf {
    fun i(tag: String, msg: String)
    fun d(tag: String, msg: String)
    fun w(tag: String, msg: String)
    fun e(tag: String, msg: String)

    fun shouldLogI(): Boolean = true
    fun shouldLogD(): Boolean = true
    fun shouldLogW(): Boolean = true
    fun shouldLogE(): Boolean = true
}