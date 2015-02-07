#!/bin/bash

java -jar ./target/pivotal-time-tracker-1.1-standalone.jar -j sprint -o /tmp/collectiveds-ptt
/usr/local/bin/aws s3 sync /tmp/collectiveds-ptt/ s3://collectiveds-ptt/ --delete

echo "Done."

exit 0