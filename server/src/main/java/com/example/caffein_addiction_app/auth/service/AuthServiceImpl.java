package com.example.caffein_addiction_app.auth.service;

import com.example.caffein_addiction_app.auth.dto.request.EditUserInfoRequestDto;
import com.example.caffein_addiction_app.auth.dto.request.LoginRequestDto;
import com.example.caffein_addiction_app.auth.dto.request.RegisterRequestDto;
import com.example.caffein_addiction_app.auth.dto.response.*;
import com.example.caffein_addiction_app.auth.entity.User;
import com.example.caffein_addiction_app.auth.repository.UserRepository;
import com.example.caffein_addiction_app.token.JwtProvider;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService{

    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;
    private final RefreshTokenService refreshTokenService;
    private final RefreshTokenBlacklistService blacklistService;

    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public ResponseEntity<? super RegisterResponseDto> register(RegisterRequestDto dto){

        try{
            boolean existedEmail = userRepository.existsByEmail(dto.getEmail());
            if(existedEmail) return RegisterResponseDto.duplicateEmail();

            String encodedPassword = passwordEncoder.encode(dto.getPassword());

            User user = new User(dto, encodedPassword);
            userRepository.save(user);
        } catch (Exception e){
            e.printStackTrace();
            return RegisterResponseDto.databaseError();
        }

        return RegisterResponseDto.success();
    }

    @Override
    public ResponseEntity<? super LoginResponseDto> login(LoginRequestDto dto) {

        String accessToken = null;
        String refreshToken = null;

        try{
            String email = dto.getEmail();
            User user = userRepository.findByEmail(email);
            if(user == null) return LoginResponseDto.loginFail();

            String password = dto.getPassword();
            String encodedPassword = user.getPassword();

            boolean isMatched = passwordEncoder.matches(password, encodedPassword);
            if(!isMatched) return LoginResponseDto.loginFail();

            Integer userId = user.getId();

            accessToken = jwtProvider.createAccessKey(userId);
            refreshToken = jwtProvider.createRefreshKey(userId);

            //redis에 refresh token 저장
            refreshTokenService.saveToken(userId, refreshToken);
        }catch (Exception e){
            e.printStackTrace();
            return LoginResponseDto.databaseError();
        }
        return LoginResponseDto.success(accessToken, refreshToken);
    }

    @Override
    public ResponseEntity<? super RefreshTokenResponseDto> refreshToken(HttpServletRequest request) {

        String refreshToken = null;

        if(request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (cookie.getName().equals("refreshToken")) {
                    refreshToken = cookie.getValue();
                    break;
                }
            }
        }

        if (refreshToken == null) return RefreshTokenResponseDto.invalidRefreshToken();

        Integer userId;

        try {
            userId = jwtProvider.validateRefreshToken(refreshToken);
        } catch (ExpiredJwtException e) {
            e.printStackTrace();
            return RefreshTokenResponseDto.expiredRefreshToken();
        } catch (Exception e) {
            e.printStackTrace();
            return RefreshTokenResponseDto.invalidRefreshToken();
        }

        if (userId == null) return RefreshTokenResponseDto.invalidRefreshToken();

        //redis에서 refresh token 가져옴
        Optional<String> refreshTokenOpt = refreshTokenService.getToken(userId);
        if(refreshTokenOpt.isEmpty()) return RefreshTokenResponseDto.invalidRefreshToken();

        String storedRefreshToken = refreshTokenOpt.get();
        if(!storedRefreshToken.equals(refreshToken)) return RefreshTokenResponseDto.invalidRefreshToken();

        //이미 사용된 토큰
        if (blacklistService.isBlacklisted(refreshToken)) return RefreshTokenResponseDto.invalidRefreshToken();

        String newAccessToken = jwtProvider.createAccessKey(userId);
        String newRefreshToken = jwtProvider.createRefreshKey(userId);

        //블랙리스트에 현재 refresh token 추가
        Duration remaining = jwtProvider.getRemainingValidity(refreshToken);
        blacklistService.blacklistToken(refreshToken, remaining);

        refreshTokenService.saveToken(userId, newRefreshToken);

        return RefreshTokenResponseDto.success(newAccessToken, newRefreshToken);
    }

    @Override
    public ResponseEntity<? super LogOutResponseDto> logout(HttpServletRequest request) {

        String bearer = null;
        String accessToken = null;

        //Authorization에서 access token 추출
        bearer = request.getHeader("Authorization");
        if (bearer == null || !bearer.startsWith("Bearer ")) return LogOutResponseDto.invalidToken();

        accessToken = bearer.substring(7);
        if(accessToken == null) return LogOutResponseDto.invalidToken();

        Integer userIdFromAccessToken;

        try {
            userIdFromAccessToken = jwtProvider.validateAccessToken(accessToken);
        } catch (Exception e) {
            e.printStackTrace();
            return LogOutResponseDto.invalidToken();
        }

        if(userIdFromAccessToken == null) return LogOutResponseDto.invalidToken();

        String refreshToken = null;
        if(request.getCookies()!=null) {
            for (Cookie cookie : request.getCookies()) {
                if (cookie.getName().equals("refreshToken")) {
                    refreshToken = cookie.getValue();
                    System.out.println(refreshToken);
                    break;
                }
            }
        }

        if(refreshToken == null) return LogOutResponseDto.invalidToken();

        Integer userIdFromRefreshToken;

        try {
            userIdFromRefreshToken = jwtProvider.validateRefreshToken(refreshToken);
        } catch (Exception e) {
            e.printStackTrace();
            return LogOutResponseDto.invalidToken();
        }

        if(userIdFromRefreshToken == null) return LogOutResponseDto.invalidToken();


        //이미 그 전에 로그아웃한 경우 -> 블랙리스트에 refresh token 들어가 있음
        if (blacklistService.isBlacklisted(refreshToken)) return LogOutResponseDto.invalidToken();

        //redis에서 refresh token 삭제
        try {
            refreshTokenService.deleteToken(userIdFromAccessToken);
        } catch (Exception e) {
            e.printStackTrace();
            return LogOutResponseDto.databaseError();
        }

        //블랙리스트에 refresh token 추가
        try {
            Duration remaining = jwtProvider.getRemainingValidity(refreshToken);
            if (!remaining.isZero()) {
                blacklistService.blacklistToken(refreshToken, remaining);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return LogOutResponseDto.databaseError();
        }


        return LogOutResponseDto.success();
    }

    @Override
    public ResponseEntity<? super GetUserInfoResponseDto> getUserInfo(Integer userId) {

        User user = null;

        try {
            Optional<User> userOpt = userRepository.findById(userId);
            if(userOpt.isEmpty()) return GetUserInfoResponseDto.notExistedUser();

            user = userOpt.get();

        } catch (Exception e) {
            e.printStackTrace();
            return GetUserInfoResponseDto.databaseError();
        }

        return GetUserInfoResponseDto.success(user);
    }

    @Override
    @Transactional
    public ResponseEntity<? super EditUserInfoResponseDto> editUserInfo(Integer userId, EditUserInfoRequestDto dto) {

        try{
            User user = userRepository.findById(userId)
                    .orElse(null);

            if(user == null) return EditUserInfoResponseDto.notExistedUser();


            Integer foundId = userRepository.findIdByEmail(dto.getEmail());
            //다른 사용자 이메일과 중복
            if(foundId != null && !foundId.equals(userId)) return EditUserInfoResponseDto.duplicateEmail();

            if (dto.getEmail() != null) user.setEmail(dto.getEmail());
            if (dto.getName() != null) user.setName(dto.getName());
            if (dto.getWeight() != null) user.setWeight(dto.getWeight());
            if (dto.getDailyCaffeineLimit() != null) user.setDailyCaffeineLimit(dto.getDailyCaffeineLimit());


            //transactional이 자동으로 update해줌
//            user.editUserInfo(dto);
//            userRepository.save(user);
        }catch (Exception e){
            e.printStackTrace();
            return EditUserInfoResponseDto.databaseError();
        }

        return EditUserInfoResponseDto.success();
    }
}
