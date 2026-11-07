# jenkins-atom-gitlab

GitLab 合并请求的**对话侧**：评论、批准、打标签。

提交状态那半已经由 `commit-status` 覆盖（它同时会说 GitLab/GitHub/Gitea），所以这里不重复；剩下的是「把构建结果说给 MR 听」。三个动作一个原子，顺带覆盖了把分析结果贴成评论的 `violation-comments-to-gitlab`。

项目路径里的 `/` 会被编码成 `%2F`（GitLab 的 API 要求），token 走 `PRIVATE-TOKEN` 头，不进日志。

```bash
mvn verify    # packages dist/gitlab-mr/ and runs src/test/java/atom/GitlabMrIT.java against a local GitLab
```
