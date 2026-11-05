# 网盘库目录布局（v1）

```
Zyll Vault/                         # Google Drive：用户可见文件夹（drive.file）
                                    # OneDrive：/drive/special/approot
├─ zyll-keybundle.json              # 密钥包，见 keybundle.md
├─ zyll-keybundle.prev.json         # 上一版本，防止写入中断
├─ HEAD.zylc                        # kind=5，加密指针：当前快照 blobId + 各设备日志水位
├─ index/snap-<blobId>.zylc         # kind=3，加密的 SQLite 快照
├─ journal/<deviceId>/<seq>.zylc    # kind=4，各设备只追加的操作日志
└─ blobs/<blobId 前 2 位>/<blobId>.zylc  # kind=0/1/2，缩略图、预览图、原图
```

- 文件名只含随机 blobId（32 位小写十六进制），不泄露任何原始信息。
- 三层图与照片的对应关系只记在加密索引中。
- Google Drive 上库根文件夹带 `appProperties.zyllVault = <vaultId>`，新设备据此发现库。
- 同步机制详见 `index/journal.md`（Phase 3 编写）。
