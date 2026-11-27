@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo.
echo  PocketPilot 官网 - 一键发布到 GitHub Pages
echo  ------------------------------------------
git add -A
git -c user.name=yuxinLi2007 -c user.email=yuxinLi2007@users.noreply.github.com commit -m "更新官网内容" || echo (没有新的改动)
git push
echo.
echo  已推送。约 1 分钟后刷新 https://yuxinLi2007.github.io/pocketpilot-site/
echo.
pause