cd /Users/clint/work/kiji/kiji-bento-chirashi
source bin/kiji-env.sh
export BENTO_LOG_ENABLE=1
bento start
export DOGFOOD_CF_HOME=/Users/clint/work/kiji/kiji-express-item-item-cf

export KIJI=kiji://.env/item_item_cf
kiji install --kiji=${KIJI}
export KIJI_CLASSPATH=target/kiji-express-item-item-cf-XXX.jar
