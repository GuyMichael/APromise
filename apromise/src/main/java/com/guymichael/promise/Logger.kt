package com.guymichael.promise

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





    fun iLazy(tag: String, messageSupplier: () -> String) {
        mLogger?.takeIf { it.shouldLogI() }?.i(tag, messageSupplier())
    }

    fun dLazy(tag: String, messageSupplier: () -> String) {
        mLogger?.takeIf { it.shouldLogD() }?.d(tag, messageSupplier())
    }

    fun wLazy(tag: String, messageSupplier: () -> String) {
        mLogger?.takeIf { it.shouldLogW() }?.i(tag, messageSupplier())
    }

    fun eLazy(tag: String, messageSupplier: () -> String) {
        mLogger?.takeIf { it.shouldLogE() }?.e(tag, messageSupplier())
    }


    fun iLazy(cls: Class<*>, messageSupplier: () -> String) {
        iLazy(cls.simpleName, messageSupplier)
    }

    fun dLazy(cls: Class<*>, messageSupplier: () -> String) {
        dLazy(cls.simpleName, messageSupplier)
    }

    fun wLazy(cls: Class<*>, messageSupplier: () -> String) {
        wLazy(cls.simpleName, messageSupplier)
    }

    fun eLazy(cls: Class<*>, messageSupplier: () -> String) {
        eLazy(cls.simpleName, messageSupplier)
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