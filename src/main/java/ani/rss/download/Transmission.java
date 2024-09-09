package ani.rss.download;

import ani.rss.entity.Config;
import ani.rss.entity.TorrentsInfo;
import ani.rss.util.ConfigUtil;
import ani.rss.util.HttpReq;
import cn.hutool.core.codec.Base64;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.Header;
import cn.hutool.http.HttpResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
public class Transmission implements BaseDownload {
    private String host = "";
    private String authorization = "";
    private String sessionId = "";

    @Override
    public Boolean login() {
        Config config = ConfigUtil.CONFIG;
        String username = config.getUsername();
        String password = config.getPassword();
        host = config.getHost();
        String downloadPath = config.getDownloadPath();

        if (StrUtil.isBlank(host) || StrUtil.isBlank(username)
                || StrUtil.isBlank(password) || StrUtil.isBlank(downloadPath)) {
            log.warn("Transmission 未配置完成");
            return false;
        }

        authorization = StrFormatter.format("Basic {}", Base64.encode(username + ":" + password));
        Boolean isOk = HttpReq.get(host, false)
                .header(Header.AUTHORIZATION, authorization)
                .thenFunction(HttpResponse::isOk);
        if (!isOk) {
            log.error("登录 Transmission 失败");
        }
        return isOk;
    }

    @Override
    public List<TorrentsInfo> getTorrentsInfos() {
        ThreadUtil.sleep(1000);
        return HttpReq.post(host + "/transmission/rpc", false)
                .header(Header.AUTHORIZATION, authorization)
                .header("X-Transmission-Session-Id", sessionId)
                .body("""
                        {
                            "arguments": {
                                "fields": [
                                    "name",
                                    "labels",
                                    "hashString",
                                    "files",
                                    "isFinished",
                                    "isStalled",
                                    "id"
                                ],
                                "format": "table"
                            },
                            "method": "torrent-get"
                        }
                        """)
                .thenFunction(res -> {
                    String id = res.header("X-Transmission-Session-Id");
                    if (StrUtil.isNotBlank(id)) {
                        sessionId = id;
                        return getTorrentsInfos();
                    }
                    List<TorrentsInfo> torrentsInfos = new ArrayList<>();
                    JsonObject jsonObject = gson.fromJson(res.body(), JsonObject.class);
                    JsonArray torrents = jsonObject.get("arguments")
                            .getAsJsonObject()
                            .get("torrents")
                            .getAsJsonArray();
                    for (int i = 0; i < torrents.size(); i++) {
                        if (i < 1) {
                            continue;
                        }
                        JsonArray asJsonArray = torrents.get(i).getAsJsonArray();
                        TorrentsInfo torrentsInfo = new TorrentsInfo();
                        torrentsInfo.setName(asJsonArray.get(0).getAsString());
                        torrentsInfo.setTags(asJsonArray.get(1).getAsJsonArray().asList().stream().map(JsonElement::getAsString).collect(Collectors.joining(",")));
                        torrentsInfo.setHash(asJsonArray.get(2).getAsString());
                        torrentsInfo.setState(asJsonArray.get(4).getAsBoolean() ? TorrentsInfo.State.pausedUP : TorrentsInfo.State.downloading);
                        torrentsInfo.setId(asJsonArray.get(6).getAsString());
                        torrentsInfos.add(torrentsInfo);
                    }
                    return torrentsInfos;
                });
    }

    @Override
    public Boolean download(String name, String savePath, File torrentFile) {
        String body = """
                {
                    "arguments": {
                        "labels":["{}"],
                        "download-dir": "{}",
                        "metainfo": "{}",
                        "paused": false
                    },
                    "method": "torrent-add"
                }
                """;
        body = StrFormatter.format(body, tag, savePath, Base64.encode(torrentFile));
        String hash = FileUtil.mainName(torrentFile);

        HttpReq.post(host + "/transmission/rpc", false)
                .header(Header.AUTHORIZATION, authorization)
                .header("X-Transmission-Session-Id", sessionId)
                .body(body)
                .then(HttpResponse::isOk);

        List<TorrentsInfo> torrentsInfos = getTorrentsInfos();
        for (int i = 0; i < 10; i++) {
            ThreadUtil.sleep(3000);
            Optional<TorrentsInfo> optionalTorrentsInfo = torrentsInfos
                    .stream()
                    .filter(torrentsInfo -> torrentsInfo.getHash().equals(hash))
                    .findFirst();
            if (optionalTorrentsInfo.isEmpty()) {
                continue;
            }
            TorrentsInfo torrentsInfo = optionalTorrentsInfo.get();
            rename(torrentsInfo, name);
            return true;
        }

        return false;
    }

    @Override
    public void delete(TorrentsInfo torrentsInfo) {
        String body = """
                {"method":"torrent-remove","arguments":{"ids":[{}],"delete-local-data":false},"tag":""}
                """;
        body = StrFormatter.format(body, torrentsInfo.getId());
        HttpReq.post(host + "/transmission/rpc", false)
                .header(Header.AUTHORIZATION, authorization)
                .header("X-Transmission-Session-Id", sessionId)
                .body(body)
                .then(res -> {
                    if (res.isOk()) {
                        log.info("删除完成");
                    }
                });
    }

    @Override
    public void rename(TorrentsInfo torrentsInfo, String reName) {
        String body = """
                {
                    "arguments": {
                        "ids": [
                            {}
                        ],
                        "name": "{}",
                        "path": "{}"
                    },
                    "method": "torrent-rename-path"
                }
                """;

        String id = torrentsInfo.getId();
        String name = torrentsInfo.getName();

        body = StrFormatter.format(body, id, name, reName);
        HttpReq.post(host + "/transmission/rpc", false)
                .header(Header.AUTHORIZATION, authorization)
                .header("X-Transmission-Session-Id", sessionId)
                .body(body)
                .then(res -> {
                    if (res.isOk()) {
                        log.info("修改完成");
                    }
                });

    }
}