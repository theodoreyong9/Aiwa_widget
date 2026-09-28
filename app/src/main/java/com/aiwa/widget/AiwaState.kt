package com.aiwa.widget
data class AiwaState(val session:String="Aiwa",val status:Status=Status.READY,val output:String="",val question:String?=null){enum class Status{READY,WORKING,WAITING,DONE,ERROR}}
