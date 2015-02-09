# `pivotal time tracker`

A quick Clojure project to hack out an automated way to get a few reports out of Pivotal Tracker.

Specifically:

1. Timesheet: Given a start date, end date and a list of project IDs, it returns a list of the tags and how many "hours" (points * a factor) each dev worked on them.

2. Sprint Report: Generates chart data (JSON files) with number of total team points, and tickets and points per developer in a project for the last 5 sprints. The output is specifically coded to work with [Panic's iOS StatusBoard App](http://panic.com/statusboard/).

## Usage

`lein uberjar`

`export PTT_TOKEN=[Pivotal Tracker API Token]`

`java -jar target/pivotal-time-tracker-2.0-standalone.jar -h`

```
  -h, --help
  -v, --verbose
  -p, --project-id PROJECT-ID             The project id, or ids (comma separated).
  -j, --job JOB                timesheet  sprint or timesheet
  -s, --start START                       Start date: 2014-09-24
  -e, --end END                           End date: 2014-10-24
  -t, --tag PREFIX             tt-        Ticket label prefix.
  -o, --outdir DIR             /tmp       Output directory.
```

## License

Copyright © 2014 [@thatmikeflynn](http://twitter.com/thatmikeflynn)

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
