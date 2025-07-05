package com.capitalEugene.common.constants

object CommunicationConstants {
    const val riskAgentEmailSubjectTemplate = "[尤金资本][风控通知][{{strategyName}}策略已被风控停止]"

    val riskAgentEmailContentTemplate = """
        <p>尊敬的用户 {{userName}}，您好：</p>
        
        <p>
        您的策略【{{strategyName}}】已于 {{stopTime}} 被系统风控自动停止，原因可能是达到止损上限或出现异常行为。
        </p>
        
        <p>
        如需恢复该策略，请尽快登录平台进行查看、操作或重新配置策略参数：<br>
        👉 <a href="{{dashboardUrl}}" target="_blank">{{dashboardUrl}}</a>
        </p>
        
        <p>若您对风控结果有任何疑问，可回复本邮件与我们联系。</p>
        
        <p>感谢您的理解与配合！</p>
        
        <div style="text-align: right; margin-top: 30px;">
          此致<br>
          尤金资本 风控中心<br>
          {{currentDate}}
        </div>
    """.trimIndent()
}