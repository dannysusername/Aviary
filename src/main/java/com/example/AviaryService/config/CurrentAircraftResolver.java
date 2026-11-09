package com.example.AviaryService.config;

import java.util.List;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.services.aircraft.AircraftNotFoundException;
import com.example.AviaryService.services.aircraft.AircraftRequiredException;

// Fills @CurrentAircraft parameters. The dashboard sends the id of the
// aircraft it was rendered for with every request (X-Aircraft-Id header, or
// the aircraftId query parameter for plain links), so two tabs on two planes
// can never write to the wrong one. The id is loaded scoped to the signed-in
// user: another account's id is a 404, same as a missing one.
// See specs/003-multi-aircraft-fleet/research.md R4.
@Component
public class CurrentAircraftResolver implements HandlerMethodArgumentResolver {

    public static final String HEADER = "X-Aircraft-Id";
    public static final String PARAM = "aircraftId";

    private final UserRepository userRepository;
    private final AircraftRepository aircraftRepository;

    public CurrentAircraftResolver(UserRepository userRepository, AircraftRepository aircraftRepository) {
        this.userRepository = userRepository;
        this.aircraftRepository = aircraftRepository;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentAircraft.class)
            && Aircraft.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Aircraft resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
            NativeWebRequest request, WebDataBinderFactory binderFactory) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User user = auth == null ? null : userRepository.findByUsername(auth.getName());
        if (user == null) throw new AircraftNotFoundException();

        String raw = request.getHeader(HEADER);
        if (raw == null || raw.isBlank()) raw = request.getParameter(PARAM);
        return resolve(user, raw);
    }

    // Shared with code that has a raw id but no web request.
    public Aircraft resolve(User user, String rawId) {
        if (rawId == null || rawId.isBlank()) {
            List<Aircraft> fleet = aircraftRepository.findByUserOrderByIdAsc(user);
            if (fleet.size() == 1) return fleet.get(0);
            throw new AircraftRequiredException();
        }
        long id;
        try {
            id = Long.parseLong(rawId.trim());
        } catch (NumberFormatException e) {
            throw new AircraftNotFoundException();
        }
        if (id <= 0) throw new AircraftNotFoundException();
        return aircraftRepository.findByIdAndUser(id, user).orElseThrow(AircraftNotFoundException::new);
    }
}
