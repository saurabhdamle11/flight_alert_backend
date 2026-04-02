package com.tracker.tracker_backend.repository;

import com.tracker.tracker_backend.entity.Region;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RegionRepository extends JpaRepository<Region, UUID> {
}
