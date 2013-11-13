cd /Users/clint/work/kiji/kiji-bento-chirashi
source bin/kiji-env.sh
bento start
export DOGFOOD_CF_HOME=/Users/clint/work/kiji/kiji-express-item-item-cf

export KIJI=kiji://.env/item_item_cf
kiji install --kiji=${KIJI}
