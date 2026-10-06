ls -la /root/vowed-upload.log /root/vowed-* 2>&1 | head -20
echo "--- processes"
ps aux | grep -E "solana|program-deploy" | grep -v grep | cut -c1-150
echo "--- uptime: $(uptime -p), now $(date -u +%H:%M:%S) UTC"
echo "--- log tail"
tail -20 /root/vowed-upload.log 2>&1
