echo "HOME=$HOME"
echo "whoami=$(whoami)  pwd=$(pwd)"
echo "WSLENV=$WSLENV"
echo "passwd home for this user: $(getent passwd "$(whoami)" | cut -d: -f6)"
ls -d /root/vowed-program/target/deploy/vowed.so
