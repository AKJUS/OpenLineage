# Copyright 2018-2025 contributors to the OpenLineage project
# SPDX-License-Identifier: Apache-2.0

import uuid
from unittest.mock import patch

from openlineage.client.uuid import generate_new_uuid
from openlineage.dagster.utils import (
    get_event_log_records,
    get_repository_name,
    make_step_job_name,
    make_step_run_id,
    to_utc_iso_8601,
)

from dagster import EventRecordsFilter
from dagster.core.events import PIPELINE_EVENTS, STEP_EVENTS

from .conftest import make_pipeline_run_with_external_pipeline_origin


def test_to_utc_iso_8601():
    assert to_utc_iso_8601(1640995200) == "2022-01-01T00:00:00.000000Z"


def test_make_step_run_id():
    run_id = make_step_run_id()
    assert uuid.UUID(run_id).version == 7


def test_make_step_job_name():
    pipeline_name = "test_job"
    step_key = "test_graph.test_op"
    assert make_step_job_name(pipeline_name, step_key) == "test_job.test_graph.test_op"


@patch("openlineage.dagster.utils.DagsterInstance")
def test_get_event_log_records(mock_instance):
    last_storage_id = 100
    record_filter_limit = 100
    event_types = PIPELINE_EVENTS | STEP_EVENTS
    get_event_log_records(mock_instance, event_types, last_storage_id, record_filter_limit)
    for event_type in event_types:
        mock_instance.get_event_records.assert_any_call(
            EventRecordsFilter(event_type=event_type, after_cursor=last_storage_id),
            limit=record_filter_limit,
        )


@patch("openlineage.dagster.utils.DagsterInstance")
def test_get_repository_name(mock_instance):
    expected_repo = "test_repo"
    pipeline_run_id = str(generate_new_uuid())
    pipeline_run = make_pipeline_run_with_external_pipeline_origin(expected_repo)
    mock_instance.get_run_by_id.return_value = pipeline_run
    actual_repo = get_repository_name(mock_instance, pipeline_run_id)

    assert expected_repo == actual_repo
    mock_instance.get_run_by_id.assert_called_once_with(pipeline_run_id)
