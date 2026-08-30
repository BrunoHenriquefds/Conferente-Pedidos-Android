[app]
title = Conferente de Pedidos Online
package.name = conferentepedidosonline
package.domain = br.com.brunohenrique
source.dir = .
source.include_exts = py,kv,png,jpg,jpeg,wav,txt,xlsx
version = 1.0.0
requirements = python3,kivy,openpyxl,requests,plyer
orientation = portrait
fullscreen = 0
android.permissions = INTERNET,READ_EXTERNAL_STORAGE,WRITE_EXTERNAL_STORAGE
android.api = 35
android.minapi = 26
android.archs = arm64-v8a,armeabi-v7a
android.accept_sdk_license = True

[buildozer]
log_level = 2
warn_on_root = 1
