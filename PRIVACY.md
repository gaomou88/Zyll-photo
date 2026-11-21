# 网络和数据

Zyll 相册没有自己的服务器，也没接任何统计、广告、崩溃上报 SDK。App 只会连这几个地方：

- Google 登录和网盘：`accounts.google.com`、`oauth2.googleapis.com`、`www.googleapis.com`
- 微软登录和 OneDrive：`login.microsoftonline.com`、`graph.microsoft.com`
- Google Play（Play 版）：内购，以及下载端侧 AI 模型

`photo.zyll.top` 和 `zyll.top` 只是关于页和外链里的网址，App 不会主动请求。外链 `#` 后面那段是钥匙，
浏览器不会把它发给任何服务器。

权限：照片和视频（包括照片里的位置信息）、网络、通知、前台服务（后台备份时显示进度）。

## 开了加密之后

原图、预览图、缩略图和备份目录（文件名、拍摄时间、相簿、哈希）都先在手机上用 AES-256-GCM 加密，再传到网盘。
密码、恢复密钥、主密钥不会离开手机。网盘上看得到的是：随机命名的密文文件、它们的数量和大小、上传时间、
密钥包（被包裹过的主密钥，见 `spec/keybundle.md`），还有几个不含照片信息的标记文件。

不开加密的话，照片和备份目录就按原样放在你自己的网盘里。

## 想自己确认

- 抓包（PCAPdroid、mitmproxy 都行），看 App 连的是不是只有上面这些地址
- 从网盘下一个备份文件，开头 4 个字节是 `ZYLC`，后面是密文；用 `kotlin/` 里的代码加上你的密码能解开
- 用 Exodus Privacy 扫一下安装包
- `cd kotlin && ./gradlew test`
