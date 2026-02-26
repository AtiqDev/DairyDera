---
trigger: always_on
---

our app is an android app that is built to perform erp operations of a dairy farm business. it was using an JavascriptInterface and we have recently refactored to replace with using WebMessageChannel such that in MainActivity.kt file we create a channel via val channel = webView!!.createWebMessageChannel(). So we need to troubleshoot the app navigation and fix any problems.

Also, we will make more modular and professional segmentation of code pieces from DatabaseHelper.kt 

Our WebView navigation is based on an SPA technique where we have defined html templates while their js files are included all in the index.html, we need to makesure there is no function , method or variable conflict caused by two different js files as all js files