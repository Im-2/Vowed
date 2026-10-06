//! Integration tests for the Vowed program, run in-process with LiteSVM.
//! Build the program first (`anchor build`); `scripts/program-build.sh` does both.
#![cfg(test)]

mod common;
mod t_checkin;
mod t_config;
mod t_demo;
mod t_math;
mod t_pool;
mod t_settle;
mod t_void;
