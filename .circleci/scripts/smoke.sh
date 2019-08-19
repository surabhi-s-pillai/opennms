#!/bin/sh -e

# If ran with 'true' then run a small subset of the tests
MINIMAL=0
if [ "$1" = "true" ]; then
  MINIMAL=1
fi

find_tests()
{
    # Generate surefire test list
    circleci tests glob "./**/src/test/java/**/*Test*.java" |\
        sed -e 's#^.*src/test/java/\(.*\)\.java#\1#' | tr "/" "." > surefire_classnames

    # Generate failsafe list
    circleci tests glob "./**/src/test/java/**/*IT*.java" |\
        sed -e 's#^.*src/test/java/\(.*\)\.java#\1#' | tr "/" "." > failsafe_classnames

    # Generate tests for this container
    < surefire_classnames circleci tests split --split-by=timings --timings-type=classname > /tmp/this_node_tests
    < failsafe_classnames circleci tests split --split-by=timings --timings-type=classname > /tmp/this_node_it_tests
}

long_tests()
{
  LIST="$1"
  if ( echo "$LIST" | grep -qs "OpsBoardAdminPageIT" ); then CCI_TEST_FORK_TIMEOUT=2100; fi
}

# Tests are forked out in separate JVMs, so the Maven runner shouldn't need a big heap
export MAVEN_OPTS="-Xms256m -Xms256m"

cd ~/project/smoke-test
if [ $MINIMAL -eq 1 ]; then
  echo "#### Executing minimal set smoke/system tests"
  # Run a set of known tests
  for TEST_CLASS in "MenuHeaderIT" "SinglePortFlowsIT"
  do
    echo "###### Testing: ${TEST_CLASS}"
    mvn -N -DskipTests=false -DskipITs=false -Dit.test=$TEST_CLASS install verify
  done
else
  echo "#### Executing complete suite of smoke/system tests"
  find_tests
  TEST_LIST="$(< /tmp/this_node_it_tests paste -s -d, -)"
  long_tests "$TEST_LIST"
  echo "###### Testing: $TEST_LIST"
  echo "###### Fork Timeout: ${CCI_TEST_FORK_TIMEOUT:-1800}"
  # Iterate through the tests and stop after the first failure
  # DFMT cannot support spaces
  export DFMT="yyyy-MM-dd;HH:mm:ss,S"
  export MAVEN_OPTS="$MAVEN_OPTS -Dorg.slf4j.simpleLogger.showDateTime=true -Dorg.slf4j.simpleLogger.dateTimeFormat=$DFMT"
  mvn -N \
      -DskipTests=false \
      -DskipITs=false \
      -DfailIfNoTests=false \
      -Dtest.fork.timeout="${CCI_TEST_FORK_TIMEOUT:-1800}" \
      -Dit.test="$TEST_LIST" \
      install verify
fi 

